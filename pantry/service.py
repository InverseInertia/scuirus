"""The only writer of events. Each operation appends to the log and rebuilds the snapshot."""

from __future__ import annotations

import threading
from dataclasses import replace
from datetime import date, datetime, timedelta, timezone
from fractions import Fraction
from typing import Callable

from . import domain
from .domain import Event, Product, Snapshot, new_id
from .matcher import normalize
from .store import Store


class NotFound(Exception):
    pass


class Invalid(Exception):
    pass


class PantryService:
    def __init__(self, store: Store, clock: Callable[[], datetime] | None = None):
        self.store = store
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.lock = threading.RLock()
        self._events = store.events()
        self._last_ts = max((e.ts for e in self._events), default=datetime.min.replace(tzinfo=timezone.utc))
        self.snapshot: Snapshot = domain.replay(self._events)

    # Reading

    def products(self) -> list[Product]:
        return self.store.products()

    def product(self, pid: str) -> Product:
        for p in self.store.products():
            if p.id == pid:
                return p
        raise NotFound(f"unknown product {pid}")

    def product_by_barcode(self, barcode: str) -> Product | None:
        code = barcode.strip()
        for p in self.store.products():
            if p.barcode == code:
                return p
        return None

    def inventory(self) -> list[dict]:
        products = {p.id: p for p in self.store.products()}
        st = domain.stats(self.snapshot, self.clock())
        by_product: dict[str, list[domain.Lot]] = {}
        for lot in self.snapshot.active_lots():
            by_product.setdefault(lot.product_id, []).append(lot)
        items = []
        for pid, lots in by_product.items():
            p = products.get(pid)
            if p is None:
                continue
            nearest = min((l.expires for l in lots if l.expires), default=None)
            items.append({"product": p, "lots": lots, "remaining": sum((l.remaining for l in lots), Fraction(0)),
                          "nearest_expiry": nearest, "stats": st.get(pid)})
        items.sort(key=lambda i: (i["nearest_expiry"] is None, i["nearest_expiry"] or date.max, i["product"].name.lower()))
        return items

    def history(self, limit: int = 100) -> list[Event]:
        return sorted(self._events, key=lambda e: (e.ts, e.id), reverse=True)[:limit]

    # Catalog

    def save_product(self, p: Product) -> Product:
        if not p.name.strip():
            raise Invalid("name is required")
        if p.default_use <= 0:
            raise Invalid("default use must be positive")
        barcode = (p.barcode or "").strip() or None
        if barcode:
            other = self.product_by_barcode(barcode)
            if other and other.id != p.id:
                raise Invalid(f"barcode {barcode} already belongs to {other.name}")
        p = replace(p, name=p.name.strip(), barcode=barcode)
        self.store.save_product(p)
        return p

    def new_product(self, name: str, **kw) -> Product:
        return self.save_product(Product(id=new_id("p", 6), name=name, **kw))

    # Adding stock

    def add_lot(self, product_id: str, qty: Fraction = Fraction(1), price: Fraction | None = None,
                bought: date | None = None, expires: date | None = None, location: str | None = None,
                source: str = "manual", note: str | None = None) -> str:
        events, lot = self._add_event(product_id, qty, price, bought, expires, location, source, note)
        self._append(events)
        return lot

    def _add_event(self, product_id, qty, price, bought, expires, location, source, note) -> tuple[list[Event], str]:
        p = self.product(product_id)
        if qty is None or qty <= 0:
            raise Invalid("quantity must be positive")
        day = bought or self.clock().date()
        exp = expires or (day + timedelta(days=p.shelf_life_days) if p.shelf_life_days else None)
        lot = new_id("l", 8)
        e = Event(id=new_id("e", 10), ts=self._next_ts(), type="add", lot=lot, product=product_id, qty=qty,
                  price=price, bought=day, expires=exp, location=location or p.location, source=source, note=note)
        return [e], lot

    # Removing stock

    def remove_from_product(self, product_id: str, qty: Fraction | None = None, discard: bool = False) -> dict:
        p = self.product(product_id)
        want = qty if qty is not None else p.default_use
        if want <= 0:
            raise Invalid("quantity must be positive")
        have = self.snapshot.remaining_for(product_id)
        take = min(want, have)
        if take <= 0:
            return {"removed": Fraction(0), "shortfall": want, "remaining": have}
        self._append([Event(id=new_id("e", 10), ts=self._next_ts(), type="discard" if discard else "consume",
                            product=product_id, qty=take, source="manual")])
        return {"removed": take, "shortfall": want - take, "remaining": have - take}

    def remove_from_lot(self, lot_id: str, qty: Fraction | None = None, discard: bool = False) -> dict:
        lot = self._lot(lot_id)
        want = qty if qty is not None else lot.remaining
        if want <= 0:
            raise Invalid("quantity must be positive")
        take = min(want, lot.remaining)
        if take <= 0:
            return {"removed": Fraction(0), "shortfall": want, "remaining": Fraction(0)}
        self._append([Event(id=new_id("e", 10), ts=self._next_ts(), type="discard" if discard else "consume",
                            lot=lot_id, product=lot.product_id, qty=take, source="manual")])
        return {"removed": take, "shortfall": want - take, "remaining": lot.remaining - take}

    # Barcode scans

    def scan(self, barcode: str, mode: str, qty: Fraction | None = None) -> dict:
        """mode is "add" or "use". Unknown barcodes come back as such so the client can offer to create the product."""
        p = self.product_by_barcode(barcode)
        if p is None:
            return {"status": "unknown", "barcode": barcode}
        if mode == "add":
            amount = qty or Fraction(1)
            lot = self.add_lot(p.id, amount, source="scan")
            return {"status": "added", "product": p, "lot": lot, "qty": amount,
                    "remaining": self.snapshot.remaining_for(p.id)}
        if mode == "use":
            have = self.snapshot.remaining_for(p.id)
            if have <= 0:
                return {"status": "out_of_stock", "product": p}
            r = self.remove_from_product(p.id, qty)
            return {"status": "used", "product": p, "qty": r["removed"], "remaining": r["remaining"]}
        raise Invalid(f"unknown scan mode {mode}")

    # Editing

    def adjust_lot(self, lot_id: str, remaining: Fraction | None = None, expires: date | None = None,
                   location: str | None = None, price: Fraction | None = None) -> None:
        lot = self._lot(lot_id)
        if remaining is not None and remaining < 0:
            raise Invalid("remaining cannot be negative")
        self._append([Event(id=new_id("e", 10), ts=self._next_ts(), type="adjust", lot=lot_id, product=lot.product_id,
                            qty=remaining, expires=expires, location=location, price=price, source="manual")])

    def void_lot(self, lot_id: str) -> None:
        lot = self._lot(lot_id)
        self._append([Event(id=new_id("e", 10), ts=self._next_ts(), type="void", lot=lot_id,
                            product=lot.product_id, source="manual")])

    # Receipts

    def commit_receipt(self, receipt_id: str, bought: date | None, lines: list[dict]) -> list[str]:
        """Writes one add event per confirmed line in a single transaction and learns aliases.

        Each line: product_id, qty, price (total paid, optional), expires (optional), raw_text (optional).
        """
        if not lines:
            raise Invalid("no lines to add")
        with self.lock:
            events: list[Event] = []
            lots = []
            for l in lines:
                evs, lot = self._add_event(l["product_id"], l["qty"], l.get("price"), bought, l.get("expires"),
                                           l.get("location"), f"receipt:{receipt_id}", None)
                events += evs
                lots.append(lot)
            self._append(events)
            for l in lines:
                raw = normalize(l.get("raw_text") or "")
                if raw:
                    self.store.save_alias(raw, l["product_id"])
            return lots

    # Internals

    def _lot(self, lot_id: str) -> domain.Lot:
        lot = self.snapshot.lots.get(lot_id)
        if lot is None or lot.voided:
            raise NotFound(f"unknown lot {lot_id}")
        return lot

    def _next_ts(self) -> datetime:
        """Strictly increasing, so a later operation never sorts before an earlier one."""
        with self.lock:
            now = self.clock().astimezone(timezone.utc)
            now = now.replace(microsecond=now.microsecond // 1000 * 1000)
            self._last_ts = now if now > self._last_ts else self._last_ts + timedelta(milliseconds=1)
            return self._last_ts

    def _append(self, events: list[Event]) -> None:
        with self.lock:
            self.store.append(events)
            self._events.extend(events)
            self.snapshot = domain.replay(self._events)
