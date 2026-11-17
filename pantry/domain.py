"""Inventory model. Stock is never stored: it is rebuilt by replaying the append-only event log.

Quantities are exact fractions, so three removals of a third empty a lot exactly.
"""

from __future__ import annotations

import secrets
import string
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from fractions import Fraction
from typing import Iterable

EVENT_TYPES = ("add", "consume", "discard", "adjust", "void")
LOCATIONS = ("pantry", "fridge", "freezer")

_ALPHABET = string.digits + string.ascii_lowercase


def new_id(prefix: str, length: int = 8) -> str:
    return prefix + "_" + "".join(secrets.choice(_ALPHABET) for _ in range(length))


def parse_qty(text: str | int | float | Fraction | None) -> Fraction | None:
    """Accepts "2", "1/3", "1.5", " 1 1/2 ". Returns None for blank or unreadable input."""
    if text is None:
        return None
    if isinstance(text, Fraction):
        return text
    if isinstance(text, int):
        return Fraction(text)
    if isinstance(text, float):
        return Fraction(str(text))
    s = text.strip()
    if not s:
        return None
    try:
        parts = s.split()
        if len(parts) == 2 and "/" in parts[1]:  # mixed number "1 1/2"
            whole = Fraction(parts[0])
            frac = Fraction(parts[1])
            return whole + frac if whole >= 0 else whole - frac
        return Fraction(s)
    except (ValueError, ZeroDivisionError):
        return None


def qty_text(q: Fraction) -> str:
    return str(q.numerator) if q.denominator == 1 else f"{q.numerator}/{q.denominator}"


def qty_display(q: Fraction) -> str:
    """Short human form: 2, 1/3, 1 1/2, or 1.42 for long decimals."""
    if q.denominator == 1:
        return str(q.numerator)
    if q.denominator in (2, 3, 4, 8):
        whole, rest = divmod(q.numerator, q.denominator)
        if whole and q > 0:
            return f"{whole} {rest}/{q.denominator}"
        return f"{q.numerator}/{q.denominator}"
    return f"{float(q):.2f}".rstrip("0").rstrip(".")


@dataclass(frozen=True)
class Product:
    id: str
    name: str
    barcode: str | None = None
    category: str | None = None
    unit: str = "each"
    pack_size: str | None = None
    shelf_life_days: int | None = None
    default_use: Fraction = Fraction(1)
    location: str = "pantry"


@dataclass(frozen=True)
class Event:
    """One line of history.

    add      creates lot `lot` of `product` with `qty`, optional total `price`, `bought`, `expires`, `location`.
    consume  removes `qty`. With `lot` it targets that lot; with only `product` it takes from the
             earliest-expiring lots first and spans lots when needed.
    discard  same as consume, counted as waste.
    adjust   sets the remaining quantity of `lot` to `qty` and updates expires/location/price if given.
    void     cancels a lot that was added by mistake, so it stops counting in stock and spending.
    """

    id: str
    ts: datetime
    type: str
    lot: str | None = None
    product: str | None = None
    qty: Fraction | None = None
    price: Fraction | None = None
    bought: date | None = None
    expires: date | None = None
    location: str | None = None
    source: str | None = None
    note: str | None = None


@dataclass
class Lot:
    id: str
    product_id: str
    initial_qty: Fraction
    remaining: Fraction
    price: Fraction | None
    bought: date | None
    expires: date | None
    location: str | None
    source: str | None
    added_at: datetime
    consumed: Fraction = Fraction(0)
    discarded: Fraction = Fraction(0)
    depleted_at: datetime | None = None
    voided: bool = False

    @property
    def active(self) -> bool:
        return not self.voided and self.remaining > 0

    @property
    def unit_price(self) -> Fraction | None:
        if self.price is None or self.initial_qty <= 0:
            return None
        return self.price / self.initial_qty


@dataclass
class Snapshot:
    lots: dict[str, Lot] = field(default_factory=dict)
    shortfalls: dict[str, Fraction] = field(default_factory=dict)
    warnings: list[tuple[str, str]] = field(default_factory=list)

    def active_lots(self, product_id: str | None = None) -> list[Lot]:
        lots = [l for l in self.lots.values() if l.active and (product_id is None or l.product_id == product_id)]
        return sorted(lots, key=fefo_key)

    def remaining_for(self, product_id: str) -> Fraction:
        return sum((l.remaining for l in self.active_lots(product_id)), Fraction(0))


def fefo_key(lot: Lot):
    """Earliest expiry first. No expiry goes last, then oldest purchase, then id for a stable tie."""
    return (lot.expires is None, lot.expires or date.max, lot.bought or date.max, lot.id)


def replay(events: Iterable[Event]) -> Snapshot:
    snap = Snapshot()
    seen: set[str] = set()
    ordered = sorted(events, key=lambda e: (e.ts, e.id))
    for e in ordered:
        if e.id in seen:
            continue
        seen.add(e.id)
        if e.type == "add":
            _add(snap, e)
        elif e.type in ("consume", "discard"):
            _remove(snap, e)
        elif e.type == "adjust":
            _adjust(snap, e)
        elif e.type == "void":
            lot = snap.lots.get(e.lot or "")
            if lot is None:
                snap.warnings.append((e.id, f"void for unknown lot {e.lot}"))
            else:
                lot.voided = True
                lot.remaining = Fraction(0)
        else:
            snap.warnings.append((e.id, f"unknown event type {e.type}"))
    return snap


def _add(snap: Snapshot, e: Event) -> None:
    if not e.lot or not e.product or e.qty is None or e.qty <= 0:
        snap.warnings.append((e.id, "add needs lot, product and a positive qty"))
        return
    if e.lot in snap.lots:
        snap.warnings.append((e.id, f"lot {e.lot} already exists, ignoring duplicate add"))
        return
    snap.lots[e.lot] = Lot(
        id=e.lot, product_id=e.product, initial_qty=e.qty, remaining=e.qty, price=e.price,
        bought=e.bought, expires=e.expires, location=e.location, source=e.source, added_at=e.ts,
    )


def _take(lot: Lot, amount: Fraction, waste: bool, ts: datetime) -> Fraction:
    took = min(amount, lot.remaining)
    if took <= 0:
        return amount
    lot.remaining -= took
    if waste:
        lot.discarded += took
    else:
        lot.consumed += took
    if lot.remaining == 0:
        lot.depleted_at = ts
    return amount - took


def _short(snap: Snapshot, product_id: str, amount: Fraction) -> None:
    snap.shortfalls[product_id] = snap.shortfalls.get(product_id, Fraction(0)) + amount


def _remove(snap: Snapshot, e: Event) -> None:
    if e.qty is None or e.qty <= 0:
        snap.warnings.append((e.id, f"{e.type} needs a positive qty"))
        return
    waste = e.type == "discard"
    if e.lot:
        lot = snap.lots.get(e.lot)
        if lot is None:
            snap.warnings.append((e.id, f"{e.type} for unknown lot {e.lot}"))
            if e.product:
                _short(snap, e.product, e.qty)
            return
        left = _take(lot, e.qty, waste, e.ts)
        if left > 0:
            _short(snap, lot.product_id, left)
        return
    if not e.product:
        snap.warnings.append((e.id, f"{e.type} needs a lot or a product"))
        return
    left = e.qty
    for lot in snap.active_lots(e.product):
        if left <= 0:
            break
        left = _take(lot, left, waste, e.ts)
    if left > 0:
        _short(snap, e.product, left)


def _adjust(snap: Snapshot, e: Event) -> None:
    lot = snap.lots.get(e.lot or "")
    if lot is None:
        snap.warnings.append((e.id, f"adjust for unknown lot {e.lot}"))
        return
    if e.qty is not None:
        if e.qty < 0:
            snap.warnings.append((e.id, "adjust ignored a negative qty"))
        else:
            lot.remaining = e.qty
            lot.depleted_at = e.ts if e.qty == 0 else None
    if e.expires is not None:
        lot.expires = e.expires
    if e.location is not None:
        lot.location = e.location
    if e.price is not None:
        lot.price = e.price


@dataclass
class ProductStats:
    lot_count: int
    bought: Fraction
    spent: Fraction | None
    average_unit_price: Fraction | None
    last_unit_price: Fraction | None
    consumed: Fraction
    discarded: Fraction
    waste_ratio: float | None
    average_days_to_use: float | None
    usage_per_day: float | None
    days_of_stock: float | None
    remaining: Fraction


def stats(snap: Snapshot, now: datetime | None = None) -> dict[str, ProductStats]:
    now = now or datetime.now(timezone.utc)
    by_product: dict[str, list[Lot]] = {}
    for lot in snap.lots.values():
        if not lot.voided:
            by_product.setdefault(lot.product_id, []).append(lot)
    out: dict[str, ProductStats] = {}
    for pid, lots in by_product.items():
        zero = Fraction(0)
        bought = sum((l.initial_qty for l in lots), zero)
        priced = [l for l in lots if l.price is not None]
        spent = sum((l.price for l in priced), zero) if priced else None
        priced_qty = sum((l.initial_qty for l in priced), zero)
        consumed = sum((l.consumed for l in lots), zero)
        discarded = sum((l.discarded for l in lots), zero)
        removed = consumed + discarded
        remaining = sum((l.remaining for l in lots), zero)
        emptied = [l for l in lots if l.depleted_at is not None and l.remaining == 0]
        avg_days = (
            sum((l.depleted_at - l.added_at).total_seconds() for l in emptied) / 86400 / len(emptied)
            if emptied else None
        )
        first = min(l.added_at for l in lots)
        days = max((now - first).total_seconds() / 86400, 1.0)
        # A rate from less than a week of history is mostly noise.
        per_day = float(removed) / days if removed > 0 and days >= 7 else None
        last_priced = max(priced, key=lambda l: l.added_at) if priced else None
        out[pid] = ProductStats(
            lot_count=len(lots),
            bought=bought,
            spent=spent,
            average_unit_price=spent / priced_qty if spent is not None and priced_qty > 0 else None,
            last_unit_price=last_priced.unit_price if last_priced else None,
            consumed=consumed,
            discarded=discarded,
            waste_ratio=float(discarded / removed) if removed > 0 else None,
            average_days_to_use=avg_days,
            usage_per_day=per_day,
            days_of_stock=float(remaining) / per_day if per_day else None,
            remaining=remaining,
        )
    return out
