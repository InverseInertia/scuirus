"""Receipt uploads: store the photos, read them in the background, and build a draft for review."""

from __future__ import annotations

import hashlib
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .. import images as imgs
from ..domain import new_id
from ..matcher import suggest
from ..service import ScuirusService
from ..store import Store
from .readers import Reader, ReaderError

log = logging.getLogger(__name__)


class ReceiptPipeline:
    def __init__(self, store: Store, service: ScuirusService, reader: Reader | None, image_dir: Path, workers: int = 1):
        self.store = store
        self.service = service
        self.reader = reader
        self.image_dir = image_dir
        image_dir.mkdir(parents=True, exist_ok=True)
        # One at a time by default: a local model on one GPU gains nothing from parallel requests.
        self.pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="receipt")
        self._lock = threading.Lock()

    def upload(self, files: list[tuple[str, bytes]], one_receipt: bool) -> dict:
        """Saves photos and queues them for reading.

        With one_receipt, all photos are parts of a single long receipt. Otherwise each photo is its
        own receipt. Photos already imported before are skipped, so a whole folder can be re-picked.
        """
        created, skipped = [], []
        groups: list[list[tuple[str, bytes, str]]] = []
        for name, data in files:
            digest = hashlib.sha256(data).hexdigest()
            existing = self.store.receipt_for_image(digest)
            if existing:
                skipped.append({"file": name, "receipt": existing})
                continue
            try:
                imgs.load(data)
            except imgs.BadImage as e:
                skipped.append({"file": name, "error": str(e)})
                continue
            entry = (name, data, digest)
            if one_receipt and groups:
                groups[0].append(entry)
            else:
                groups.append([entry])

        for group in groups:
            rid = new_id("r", 8)
            folder = self.image_dir / rid
            folder.mkdir(parents=True, exist_ok=True)
            paths = []
            for i, (_, data, _) in enumerate(group):
                img = imgs.load(data)
                path = folder / f"{i + 1}.jpg"
                path.write_bytes(imgs.to_jpeg(img, quality=92))
                paths.append(f"{rid}/{path.name}")
            self.store.create_receipt(rid, paths, [g[2] for g in group], self.reader.name if self.reader else "none")
            created.append(rid)
            self.pool.submit(self.process, rid)
        return {"created": created, "skipped": skipped}

    def process(self, rid: str) -> None:
        rec = self.store.receipt(rid)
        if rec is None:
            return
        if self.reader is None:
            self.store.update_receipt(rid, status="error", error="No receipt reader configured. See README: Receipt reading.")
            return
        self.store.update_receipt(rid, status="processing", error=None, backend=self.reader.name)
        try:
            photos = [imgs.load((self.image_dir / p).read_bytes()) for p in rec["images"]]
            result = self.reader.read(photos)
            self.store.update_receipt(rid, status="ready", extraction=result.model_dump())
        except ReaderError as e:
            log.warning("receipt %s: %s", rid, e)
            self.store.update_receipt(rid, status="error", error=str(e))
        except Exception as e:  # keep the worker alive; show the error on the receipt
            log.exception("receipt %s failed", rid)
            self.store.update_receipt(rid, status="error", error=f"Unexpected error: {e}")

    def retry(self, rid: str) -> None:
        self.store.update_receipt(rid, status="processing", error=None)
        self.pool.submit(self.process, rid)

    def delete(self, rid: str) -> None:
        """Hides the receipt and frees its photos for re-import. Stock already added stays."""
        self.store.update_receipt(rid, status="deleted")
        self.store.forget_receipt_images(rid)

    def draft(self, rid: str) -> dict | None:
        """The receipt plus, per line, ranked product suggestions and the checks the user should see."""
        rec = self.store.receipt(rid)
        if rec is None:
            return None
        ex = rec["extraction"]
        if not ex:
            return {**rec, "lines": [], "checks": []}
        products = self.service.products()
        aliases = self.store.aliases()
        lines = []
        for i, item in enumerate(ex["items"]):
            lines.append({
                "index": i,
                **item,
                "suggestions": suggest(item["raw_text"], item["name"], products, aliases),
            })
        return {**rec, "lines": lines, "checks": checks(ex)}


def checks(ex: dict) -> list[str]:
    """Cross-checks the reading against the printed totals, so a misread line is visible before adding."""
    out = []
    prices = [i["line_total"] for i in ex["items"]]
    missing = sum(1 for p in prices if p is None)
    if missing:
        out.append(f"{missing} line{'s' if missing > 1 else ''} without a readable price.")
    total_lines = round(sum(p for p in prices if p is not None), 2)
    if ex.get("subtotal") is not None and not missing:
        diff = round(ex["subtotal"] - total_lines, 2)
        if abs(diff) > 0.02:
            out.append(f"Lines add up to {total_lines:.2f} but the receipt subtotal is {ex['subtotal']:.2f} "
                       f"({'+' if diff > 0 else ''}{diff:.2f}). A line may be missing or misread.")
    elif ex.get("total") is not None and ex.get("tax") is not None and not missing:
        diff = round(ex["total"] - ex["tax"] - total_lines, 2)
        if abs(diff) > 0.02:
            out.append(f"Lines add up to {total_lines:.2f} but total minus tax is {ex['total'] - ex['tax']:.2f}.")
    if not ex.get("date"):
        out.append("No purchase date found; today's date will be used.")
    if ex.get("notes"):
        out.append(f"Reader's note: {ex['notes']}")
    return out
