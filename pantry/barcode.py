"""Server-side barcode decoding with zxing-cpp."""

from __future__ import annotations

import zxingcpp
from PIL import Image, ImageOps

from .images import fit, load

RETAIL_FORMATS = ("EAN13", "EAN8", "UPCA", "UPCE", "Code128", "Code39", "ITF", "DataBar", "DataBarExpanded", "QRCode")


def _formats():
    found = [getattr(zxingcpp.BarcodeFormat, n) for n in RETAIL_FORMATS if hasattr(zxingcpp.BarcodeFormat, n)]
    try:  # zxing-cpp 3 takes a list
        zxingcpp.read_barcodes(Image.new("L", (8, 8)), formats=found)
        return found
    except TypeError:  # zxing-cpp 2 takes flags joined with |
        flags = found[0]
        for f in found[1:]:
            flags |= f
        return flags


_FORMATS = _formats()


def normalize_code(text: str, fmt: str) -> str:
    """Stores UPC-A as its 12 digits. zxing reports some UPC-A codes as EAN-13 with a leading zero."""
    code = text.strip()
    if fmt in ("EAN13", "EAN-13") and len(code) == 13 and code.startswith("0") and code.isdigit():
        return code[1:]
    return code


def decode_image(img: Image.Image) -> list[dict]:
    """Returns every barcode found, trying a downscaled and a contrast-boosted copy if the first pass fails."""
    attempts = [fit(img, 1600)]
    attempts.append(ImageOps.autocontrast(ImageOps.grayscale(attempts[0])))
    for candidate in attempts:
        kw = {"try_rotate": True, "try_downscale": True}
        if _FORMATS is not None:
            kw["formats"] = _FORMATS
        try:
            results = zxingcpp.read_barcodes(candidate, **kw)
        except TypeError:  # older zxing-cpp without some keyword arguments
            results = zxingcpp.read_barcodes(candidate)
        found = []
        for r in results:
            if not r.valid or not r.text:
                continue
            fmt = r.format.name if hasattr(r.format, "name") else str(r.format)
            found.append({"code": normalize_code(r.text, fmt), "format": fmt})
        if found:
            return found
    return []


def decode(data: bytes) -> list[dict]:
    return decode_image(load(data))
