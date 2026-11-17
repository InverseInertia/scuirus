from __future__ import annotations

import io
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
import zxingcpp
from PIL import Image, ImageDraw

from scuirus.config import Config
from scuirus.receipts.schema import Receipt, ReceiptItem
from scuirus.service import ScuirusService
from scuirus.store import Store


class Clock:
    def __init__(self, start=datetime(2026, 11, 1, 12, 0, tzinfo=timezone.utc)):
        self.now = start

    def __call__(self):
        return self.now

    def advance(self, **kw):
        self.now += timedelta(**kw)


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def service(tmp_path: Path, clock):
    return ScuirusService(Store(tmp_path / "db.sqlite3"), clock=clock)


def barcode_png(code: str, fmt=zxingcpp.BarcodeFormat.UPCA, scale=4, pad=40, rotate=0) -> bytes:
    """Renders a barcode the way a phone photo of a package might look: padded, optionally rotated."""
    b = zxingcpp.create_barcode(code, fmt)
    raw = zxingcpp.write_barcode_to_image(b, scale=scale)
    mv = memoryview(raw)
    img = Image.frombytes("L", (mv.shape[1], mv.shape[0]), bytes(mv))
    canvas = Image.new("L", (img.width + 2 * pad, img.height + 2 * pad + 80), 255)
    canvas.paste(img, (pad, pad))
    ImageDraw.Draw(canvas).text((pad, img.height + pad + 20), "MILK 1 GAL", fill=0)
    if rotate:
        canvas = canvas.rotate(rotate, expand=True, fillcolor=255)
    buf = io.BytesIO()
    canvas.convert("RGB").save(buf, "PNG")
    return buf.getvalue()


def photo_bytes(w=600, h=900, color="white", fmt="JPEG") -> bytes:
    buf = io.BytesIO()
    img = Image.new("RGB", (w, h), color)
    ImageDraw.Draw(img).text((20, 20), f"{w}x{h} {color}", fill="black")
    img.save(buf, fmt)
    return buf.getvalue()


class FakeReader:
    """Stands in for a vision model. Returns a fixed reading and records what it was shown."""

    name = "fake"

    def __init__(self, receipt: Receipt | None = None, error: Exception | None = None):
        self.receipt = receipt or sample_receipt()
        self.error = error
        self.calls: list[list[Image.Image]] = []

    def read(self, photos):
        self.calls.append(photos)
        if self.error:
            raise self.error
        return self.receipt


def item(raw, name, qty=1.0, unit="each", total=None, discount=None, food=True, cat="pantry"):
    return ReceiptItem(raw_text=raw, name=name, quantity=qty, unit=unit, line_total=total, discount=discount,
                       is_food=food, category=cat)


def sample_receipt() -> Receipt:
    return Receipt(
        store="Corner Grocer", date="2026-10-30",
        items=[
            item("ORG WHL MLK 1GAL", "Organic whole milk", total=5.49, cat="dairy"),
            item("BANANAS", "Bananas", qty=2.31, unit="lb", total=1.36, cat="produce"),
            item("BNLS CHKN BRST", "Boneless chicken breast", total=9.12, discount=1.00, cat="meat"),
            item("PAPER BAG", "Paper bag", total=0.10, food=False, cat="other"),
        ],
        subtotal=16.07, tax=0.01, total=16.08, notes=None,
    )


@pytest.fixture
def make_client(tmp_path: Path):
    from fastapi.testclient import TestClient

    from scuirus.web import create_app

    def make(reader=None, lookup=None):
        cfg = Config(data_dir=tmp_path / "data", reader="none")
        app = create_app(cfg, reader=reader, lookup=lookup or (lambda code: None))
        client = TestClient(app)
        client.app_state = app.state
        return client

    return make
