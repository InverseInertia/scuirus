"""HTTP API and static frontend."""

from __future__ import annotations

import logging
from dataclasses import asdict, replace
from datetime import date
from fractions import Fraction
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import barcode as bc
from . import images as imgs
from . import openfoodfacts
from .config import Config
from .domain import LOCATIONS, Lot, Product, ProductStats, parse_qty, qty_display, qty_text
from .receipts.pipeline import ReceiptPipeline
from .receipts.readers import ClaudeReader, OllamaReader, Reader
from .service import Invalid, NotFound, ScuirusService
from .store import Store, ts_text

log = logging.getLogger(__name__)
STATIC = Path(__file__).parent / "static"


# Serialization. Quantities go out as exact text ("1/3") plus a display form.

def q_out(q: Fraction | None) -> dict | None:
    if q is None:
        return None
    return {"exact": qty_text(q), "display": qty_display(q), "value": float(q)}


def money(q: Fraction | None) -> float | None:
    return None if q is None else round(float(q), 2)


def product_out(p: Product) -> dict:
    d = asdict(p)
    d["default_use"] = q_out(p.default_use)
    return d


def lot_out(l: Lot) -> dict:
    return {
        "id": l.id, "product_id": l.product_id, "initial": q_out(l.initial_qty), "remaining": q_out(l.remaining),
        "price": money(l.price), "bought": l.bought, "expires": l.expires, "location": l.location, "source": l.source,
    }


def stats_out(s: ProductStats | None) -> dict | None:
    if s is None:
        return None
    return {
        "spent": money(s.spent), "average_unit_price": money(s.average_unit_price),
        "last_unit_price": money(s.last_unit_price), "consumed": q_out(s.consumed), "discarded": q_out(s.discarded),
        "waste_ratio": s.waste_ratio, "average_days_to_use": s.average_days_to_use,
        "usage_per_day": s.usage_per_day, "days_of_stock": s.days_of_stock, "lot_count": s.lot_count,
    }


def need_qty(text: str | None, field: str = "qty", default: Fraction | None = None) -> Fraction | None:
    if text is None or str(text).strip() == "":
        return default
    q = parse_qty(str(text))
    if q is None:
        raise Invalid(f"{field} must be a number or fraction like 1/3")
    return q


def need_date(text: str | None) -> date | None:
    if not text:
        return None
    try:
        return date.fromisoformat(text)
    except ValueError:
        raise Invalid(f"bad date {text}")


# Request bodies

class ProductIn(BaseModel):
    name: str
    barcode: str | None = None
    category: str | None = None
    unit: str = "each"
    pack_size: str | None = None
    shelf_life_days: int | None = None
    default_use: str = "1"
    location: str = "pantry"


class AddIn(BaseModel):
    product_id: str
    qty: str = "1"
    price: str | None = None
    bought: str | None = None
    expires: str | None = None
    location: str | None = None


class UseIn(BaseModel):
    qty: str | None = None
    discard: bool = False


class AdjustIn(BaseModel):
    remaining: str | None = None
    expires: str | None = None
    location: str | None = None
    price: str | None = None


class ScanIn(BaseModel):
    barcode: str
    mode: str
    qty: str | None = None


class NewProductIn(BaseModel):
    name: str
    unit: str = "each"
    location: str = "pantry"
    category: str | None = None
    shelf_life_days: int | None = None


class CommitLineIn(BaseModel):
    index: int | None = None
    product_id: str | None = None
    new_product: NewProductIn | None = None
    raw_text: str | None = None
    qty: str
    price: str | None = None
    expires: str | None = None
    location: str | None = None


class CommitIn(BaseModel):
    date: str | None = None
    lines: list[CommitLineIn]


def make_reader(cfg: Config) -> Reader | None:
    if cfg.reader == "claude":
        try:
            return ClaudeReader(model=cfg.claude_model, effort=cfg.claude_effort, api_key=cfg.claude_api_key)
        except Exception as e:  # missing credentials surface per receipt instead of blocking startup
            log.warning("Claude reader unavailable: %s", e)
            return None
    if cfg.reader == "ollama":
        return OllamaReader(url=cfg.ollama_url, model=cfg.ollama_model)
    return None


def create_app(cfg: Config | None = None, reader: Reader | None = None,
               lookup: Callable[[str], dict | None] | None = None) -> FastAPI:
    cfg = cfg or Config.load()
    store = Store(cfg.data_dir / "scuirus.sqlite3")
    service = ScuirusService(store)
    reader = reader if reader is not None else make_reader(cfg)
    image_dir = cfg.data_dir / "receipts"
    pipeline = ReceiptPipeline(store, service, reader, image_dir)
    lookup = lookup or (lambda code: openfoodfacts.lookup(code, cfg.off_contact))

    app = FastAPI(title="Scuirus")
    app.state.service = service
    app.state.pipeline = pipeline
    app.state.store = store

    @app.exception_handler(Invalid)
    async def _invalid(_, e: Invalid):
        return JSONResponse({"detail": str(e)}, status_code=400)

    @app.exception_handler(NotFound)
    async def _missing(_, e: NotFound):
        return JSONResponse({"detail": str(e)}, status_code=404)

    # Status

    @app.get("/api/status")
    def status():
        warnings = [{"event": eid, "message": m} for eid, m in service.snapshot.warnings]
        return {"reader": reader.name if reader else None, "locations": LOCATIONS, "warnings": warnings}

    # Inventory

    @app.get("/api/inventory")
    def inventory():
        return [
            {"product": product_out(i["product"]), "remaining": q_out(i["remaining"]),
             "nearest_expiry": i["nearest_expiry"], "lots": [lot_out(l) for l in i["lots"]],
             "stats": stats_out(i["stats"])}
            for i in service.inventory()
        ]

    @app.get("/api/history")
    def history(limit: int = 100):
        products = {p.id: p for p in service.products()}
        return [
            {"id": e.id, "ts": ts_text(e.ts), "type": e.type, "product": e.product, "name": products[e.product].name if e.product in products else None,
             "unit": products[e.product].unit if e.product in products else None,
             "lot": e.lot, "qty": q_out(e.qty), "price": money(e.price), "source": e.source}
            for e in service.history(limit)
        ]

    # Products

    @app.get("/api/products")
    def products():
        return [product_out(p) for p in service.products()]

    def _product_from(body: ProductIn, pid: str) -> Product:
        return Product(id=pid, name=body.name, barcode=body.barcode, category=body.category, unit=body.unit or "each",
                       pack_size=body.pack_size, shelf_life_days=body.shelf_life_days,
                       default_use=need_qty(body.default_use, "default use", Fraction(1)), location=body.location)

    @app.post("/api/products")
    def create_product(body: ProductIn):
        from .domain import new_id
        return product_out(service.save_product(_product_from(body, new_id("p", 6))))

    @app.put("/api/products/{pid}")
    def update_product(pid: str, body: ProductIn):
        service.product(pid)
        return product_out(service.save_product(_product_from(body, pid)))

    @app.get("/api/lookup/{code}")
    def lookup_barcode(code: str):
        p = service.product_by_barcode(code)
        if p:
            return {"known": True, "product": product_out(p)}
        return {"known": False, "suggestion": lookup(code)}

    # Barcodes

    @app.post("/api/barcode")
    async def decode_barcode(image: UploadFile = File(...)):
        data = await image.read()
        try:
            return {"codes": bc.decode(data)}
        except imgs.BadImage as e:
            raise HTTPException(400, str(e))

    @app.post("/api/scan")
    def scan(body: ScanIn):
        r = service.scan(body.barcode, body.mode, need_qty(body.qty))
        out = {"status": r["status"], "barcode": body.barcode}
        if "product" in r:
            out["product"] = product_out(r["product"])
        for k in ("qty", "remaining"):
            if k in r:
                out[k] = q_out(r[k])
        if "lot" in r:
            out["lot"] = r["lot"]
        return out

    # Stock changes

    @app.post("/api/lots")
    def add_lot(body: AddIn):
        lot = service.add_lot(body.product_id, need_qty(body.qty, default=Fraction(1)), need_qty(body.price, "price"),
                              need_date(body.bought), need_date(body.expires), body.location or None)
        return {"lot": lot}

    @app.post("/api/products/{pid}/use")
    def use_product(pid: str, body: UseIn):
        r = service.remove_from_product(pid, need_qty(body.qty), body.discard)
        return {k: q_out(v) for k, v in r.items()}

    @app.post("/api/lots/{lot_id}/use")
    def use_lot(lot_id: str, body: UseIn):
        r = service.remove_from_lot(lot_id, need_qty(body.qty), body.discard)
        return {k: q_out(v) for k, v in r.items()}

    @app.post("/api/lots/{lot_id}/adjust")
    def adjust_lot(lot_id: str, body: AdjustIn):
        service.adjust_lot(lot_id, need_qty(body.remaining, "remaining"), need_date(body.expires),
                           body.location or None, need_qty(body.price, "price"))
        return {"ok": True}

    @app.post("/api/lots/{lot_id}/void")
    def void_lot(lot_id: str):
        service.void_lot(lot_id)
        return {"ok": True}

    # Receipts

    @app.post("/api/receipts")
    async def upload_receipts(files: list[UploadFile] = File(...), one_receipt: bool = Form(False)):
        payload = [(f.filename or "photo", await f.read()) for f in files]
        return pipeline.upload(payload, one_receipt)

    @app.get("/api/receipts")
    def list_receipts():
        out = []
        for r in store.receipts():
            ex = r["extraction"] or {}
            out.append({"id": r["id"], "created_at": r["created_at"], "status": r["status"], "error": r["error"],
                        "images": r["images"], "store": ex.get("store"), "date": ex.get("date"),
                        "total": ex.get("total"), "items": len(ex.get("items", []))})
        return out

    @app.get("/api/receipts/{rid}")
    def get_receipt(rid: str):
        d = pipeline.draft(rid)
        if d is None or d["status"] == "deleted":
            raise HTTPException(404, "unknown receipt")
        for line in d["lines"]:
            line["suggestions"] = [{**s, "product": product_out(s["product"])} for s in line["suggestions"]]
        return d

    @app.post("/api/receipts/{rid}/retry")
    def retry_receipt(rid: str):
        if store.receipt(rid) is None:
            raise HTTPException(404, "unknown receipt")
        pipeline.retry(rid)
        return {"ok": True}

    @app.delete("/api/receipts/{rid}")
    def delete_receipt(rid: str):
        if store.receipt(rid) is None:
            raise HTTPException(404, "unknown receipt")
        pipeline.delete(rid)
        return {"ok": True}

    @app.post("/api/receipts/{rid}/commit")
    def commit_receipt(rid: str, body: CommitIn):
        rec = store.receipt(rid)
        if rec is None:
            raise HTTPException(404, "unknown receipt")
        if rec["status"] == "committed":
            raise Invalid("this receipt was already added")
        lines = []
        with service.lock:
            for l in body.lines:
                qty = need_qty(l.qty)
                if qty is None or qty <= 0:
                    raise Invalid("every line needs a positive quantity")
                if l.product_id:
                    pid = service.product(l.product_id).id
                elif l.new_product:
                    pid = None
                else:
                    raise Invalid("every line needs a product")
                lines.append((l, pid, qty))
            resolved = []
            for l, pid, qty in lines:
                if pid is None:
                    np = l.new_product
                    pid = service.new_product(np.name, unit=np.unit or "each", location=np.location or "pantry",
                                              category=np.category, shelf_life_days=np.shelf_life_days).id
                resolved.append({"product_id": pid, "qty": qty, "price": need_qty(l.price, "price"),
                                 "expires": need_date(l.expires), "location": l.location or None,
                                 "raw_text": l.raw_text})
            lots = service.commit_receipt(rid, need_date(body.date), resolved)
            store.update_receipt(rid, status="committed", committed_at=ts_text(service.clock()))
        return {"lots": lots}

    @app.get("/receipt-images/{rid}/{name}")
    def receipt_image(rid: str, name: str):
        path = (image_dir / rid / name).resolve()
        if image_dir.resolve() not in path.parents or not path.is_file():
            raise HTTPException(404)
        return FileResponse(path, media_type="image/jpeg")

    app.mount("/", StaticFiles(directory=STATIC, html=True), name="static")
    return app
