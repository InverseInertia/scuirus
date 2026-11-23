"""SQLite persistence. Events are append-only: nothing in this module updates or deletes one."""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import date, datetime, timezone
from fractions import Fraction
from pathlib import Path
from typing import Any

from .domain import Event, Product, parse_qty, qty_text

SCHEMA = """
CREATE TABLE IF NOT EXISTS products (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    barcode TEXT UNIQUE,
    category TEXT,
    unit TEXT NOT NULL DEFAULT 'each',
    pack_size TEXT,
    shelf_life_days INTEGER,
    default_use TEXT NOT NULL DEFAULT '1',
    location TEXT NOT NULL DEFAULT 'pantry'
);
CREATE TABLE IF NOT EXISTS events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    ts TEXT NOT NULL,
    type TEXT NOT NULL,
    lot TEXT,
    product TEXT,
    qty TEXT,
    price TEXT,
    bought TEXT,
    expires TEXT,
    location TEXT,
    source TEXT,
    note TEXT
);
CREATE TABLE IF NOT EXISTS aliases (
    text TEXT PRIMARY KEY,
    product_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS receipts (
    id TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    status TEXT NOT NULL,
    images TEXT NOT NULL,
    backend TEXT,
    extraction TEXT,
    error TEXT,
    committed_at TEXT
);
CREATE TABLE IF NOT EXISTS receipt_images (
    sha256 TEXT PRIMARY KEY,
    receipt_id TEXT NOT NULL
);
"""

TS_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"


def ts_text(ts: datetime) -> str:
    return ts.astimezone(timezone.utc).strftime(TS_FORMAT)


def parse_ts(text: str) -> datetime:
    return datetime.strptime(text, TS_FORMAT).replace(tzinfo=timezone.utc)


def _d(text: str | None) -> date | None:
    return date.fromisoformat(text) if text else None


def _q(q: Fraction | None) -> str | None:
    return qty_text(q) if q is not None else None


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)

    # Products

    def products(self) -> list[Product]:
        rows = self.db.execute("SELECT * FROM products ORDER BY name COLLATE NOCASE").fetchall()
        return [self._product(r) for r in rows]

    @staticmethod
    def _product(r: sqlite3.Row) -> Product:
        return Product(
            id=r["id"], name=r["name"], barcode=r["barcode"], category=r["category"], unit=r["unit"],
            pack_size=r["pack_size"], shelf_life_days=r["shelf_life_days"],
            default_use=parse_qty(r["default_use"]) or Fraction(1), location=r["location"],
        )

    def save_product(self, p: Product) -> None:
        with self.lock:
            self.db.execute(
                """INSERT INTO products (id, name, barcode, category, unit, pack_size, shelf_life_days, default_use, location)
                   VALUES (?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(id) DO UPDATE SET name=excluded.name, barcode=excluded.barcode,
                     category=excluded.category, unit=excluded.unit, pack_size=excluded.pack_size,
                     shelf_life_days=excluded.shelf_life_days, default_use=excluded.default_use,
                     location=excluded.location""",
                (p.id, p.name, p.barcode, p.category, p.unit, p.pack_size, p.shelf_life_days,
                 qty_text(p.default_use), p.location),
            )

    # Events

    def events(self) -> list[Event]:
        rows = self.db.execute("SELECT * FROM events ORDER BY seq").fetchall()
        return [
            Event(
                id=r["id"], ts=parse_ts(r["ts"]), type=r["type"], lot=r["lot"], product=r["product"],
                qty=parse_qty(r["qty"]), price=parse_qty(r["price"]), bought=_d(r["bought"]),
                expires=_d(r["expires"]), location=r["location"], source=r["source"], note=r["note"],
            )
            for r in rows
        ]

    def append(self, events: list[Event]) -> None:
        """Appends several events in one transaction, so a receipt commit lands all or nothing."""
        with self.lock:
            self.db.execute("BEGIN")
            try:
                for e in events:
                    self.db.execute(
                        """INSERT INTO events (id, ts, type, lot, product, qty, price, bought, expires, location, source, note)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                        (e.id, ts_text(e.ts), e.type, e.lot, e.product, _q(e.qty), _q(e.price),
                         e.bought.isoformat() if e.bought else None, e.expires.isoformat() if e.expires else None,
                         e.location, e.source, e.note),
                    )
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    # Aliases: normalized receipt text -> product id

    def aliases(self) -> dict[str, str]:
        return {r["text"]: r["product_id"] for r in self.db.execute("SELECT * FROM aliases")}

    def save_alias(self, text: str, product_id: str) -> None:
        with self.lock:
            self.db.execute(
                "INSERT INTO aliases (text, product_id) VALUES (?,?) ON CONFLICT(text) DO UPDATE SET product_id=excluded.product_id",
                (text, product_id),
            )

    # Receipts

    def create_receipt(self, rid: str, images: list[str], hashes: list[str], backend: str) -> None:
        with self.lock:
            self.db.execute("BEGIN")
            try:
                self.db.execute(
                    "INSERT INTO receipts (id, created_at, status, images, backend) VALUES (?,?,?,?,?)",
                    (rid, ts_text(datetime.now(timezone.utc)), "processing", json.dumps(images), backend),
                )
                for h in hashes:
                    self.db.execute("INSERT INTO receipt_images (sha256, receipt_id) VALUES (?,?)", (h, rid))
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def receipt_for_image(self, sha256: str) -> str | None:
        r = self.db.execute(
            """SELECT ri.receipt_id FROM receipt_images ri JOIN receipts r ON r.id = ri.receipt_id
               WHERE ri.sha256 = ? AND r.status != 'deleted'""",
            (sha256,),
        ).fetchone()
        return r[0] if r else None

    def update_receipt(self, rid: str, **fields: Any) -> None:
        if "extraction" in fields and fields["extraction"] is not None and not isinstance(fields["extraction"], str):
            fields["extraction"] = json.dumps(fields["extraction"])
        cols = ", ".join(f"{k}=?" for k in fields)
        with self.lock:
            self.db.execute(f"UPDATE receipts SET {cols} WHERE id=?", (*fields.values(), rid))

    def forget_receipt_images(self, rid: str) -> None:
        with self.lock:
            self.db.execute("DELETE FROM receipt_images WHERE receipt_id=?", (rid,))

    def receipt(self, rid: str) -> dict | None:
        r = self.db.execute("SELECT * FROM receipts WHERE id=?", (rid,)).fetchone()
        return self._receipt(r) if r else None

    def receipts(self, include_done: bool = True) -> list[dict]:
        q = "SELECT * FROM receipts WHERE status != 'deleted'"
        if not include_done:
            q += " AND status NOT IN ('committed')"
        return [self._receipt(r) for r in self.db.execute(q + " ORDER BY created_at DESC")]

    @staticmethod
    def _receipt(r: sqlite3.Row) -> dict:
        d = dict(r)
        d["images"] = json.loads(d["images"])
        d["extraction"] = json.loads(d["extraction"]) if d["extraction"] else None
        return d
