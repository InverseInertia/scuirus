"""Barcode lookup at Open Food Facts, used to pre-fill the new-product form."""

from __future__ import annotations

import httpx

FIELDS = "code,product_name,brands,quantity,categories_tags"


def url(barcode: str) -> str:
    digits = "".join(c for c in barcode if c.isdigit())
    return f"https://world.openfoodfacts.org/api/v2/product/{digits}.json?fields={FIELDS}"


def parse(barcode: str, body: dict) -> dict | None:
    """Returns name/brand/pack_size/category, or None when the barcode is unknown or has no name."""
    p = body.get("product")
    if not isinstance(p, dict) or body.get("status") not in (None, 1):
        return None
    name = (p.get("product_name") or "").strip()
    if not name:
        return None
    brand = ((p.get("brands") or "").split(",")[0]).strip() or None
    tags = p.get("categories_tags") or []
    category = tags[-1].split(":", 1)[-1].replace("-", " ") if tags else None
    display = f"{brand} {name}" if brand and brand.lower() not in name.lower() else name
    return {"barcode": barcode, "name": display, "brand": brand,
            "pack_size": (p.get("quantity") or "").strip() or None, "category": category or None}


def lookup(barcode: str, contact: str | None, client: httpx.Client | None = None) -> dict | None:
    """Open Food Facts asks API users to send a User-Agent naming the app and a contact."""
    ua = f"PantrySelfHosted/0.2 ({contact})" if contact else "PantrySelfHosted/0.2"
    http = client or httpx.Client(timeout=6)
    try:
        r = http.get(url(barcode), headers={"User-Agent": ua, "Accept": "application/json"})
        if r.status_code != 200:
            return None
        return parse(barcode, r.json())
    except (httpx.HTTPError, ValueError):
        return None
