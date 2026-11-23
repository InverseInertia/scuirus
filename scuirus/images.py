"""Image loading shared by the barcode decoder and the receipt reader."""

from __future__ import annotations

import io

from PIL import Image, ImageOps

try:  # iPhones and some Android camera apps save HEIC.
    import pillow_heif

    pillow_heif.register_heif_opener()
except ImportError:  # pragma: no cover
    pass


class BadImage(Exception):
    pass


def load(data: bytes) -> Image.Image:
    """Opens an upload and applies the EXIF rotation, so a phone photo comes out upright."""
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Exception as e:  # Pillow raises many types for bad input
        raise BadImage(f"not a readable image: {e}") from e
    img = ImageOps.exif_transpose(img)
    if img.mode not in ("RGB", "L"):
        img = img.convert("RGB")
    return img


def to_jpeg(img: Image.Image, quality: int = 90) -> bytes:
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=quality)
    return buf.getvalue()


def fit(img: Image.Image, max_side: int) -> Image.Image:
    w, h = img.size
    scale = max_side / max(w, h)
    if scale >= 1:
        return img
    return img.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)


def tiles(img: Image.Image, max_aspect: float = 1.6, overlap: float = 0.12) -> list[Image.Image]:
    """Cuts a tall receipt photo into overlapping slices.

    Vision models shrink each image to a fixed pixel budget. A long receipt shrunk whole becomes a
    thin strip with unreadable text, so it is sent as several slices each closer to a page shape.
    """
    w, h = img.size
    tile_h = int(w * max_aspect)
    if h <= tile_h * 1.15:
        return [img]
    step = int(tile_h * (1 - overlap))
    out = []
    top = 0
    while True:
        bottom = min(top + tile_h, h)
        out.append(img.crop((0, top, w, bottom)))
        if bottom >= h:
            break
        top += step
        if h - top < tile_h * 0.35:  # fold a short remainder into the last slice
            out[-1] = img.crop((0, top - step, w, h))
            break
    return out
