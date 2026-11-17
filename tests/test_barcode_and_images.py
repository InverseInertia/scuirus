import zxingcpp
from PIL import Image

from scuirus import barcode, images
from tests.conftest import barcode_png, photo_bytes


def test_upca_decodes_to_twelve_digits():
    assert [c["code"] for c in barcode.decode(barcode_png("036000291452"))] == ["036000291452"]


def test_ean13_kept_whole():
    codes = barcode.decode(barcode_png("4006381333931", zxingcpp.BarcodeFormat.EAN13))
    assert codes[0]["code"] == "4006381333931"


def test_rotated_barcode_still_decodes():
    assert barcode.decode(barcode_png("036000291452", rotate=90))[0]["code"] == "036000291452"


def test_blank_image_has_no_codes():
    assert barcode.decode(photo_bytes()) == []


def test_exif_rotation_applied():
    import io

    img = Image.new("RGB", (400, 200), "white")
    exif = img.getexif()
    exif[0x0112] = 6  # rotate 90 degrees clockwise on display
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif)
    assert images.load(buf.getvalue()).size == (200, 400)


def test_bad_upload_rejected():
    import pytest

    with pytest.raises(images.BadImage):
        images.load(b"not an image")


def test_tall_receipt_sliced_with_overlap():
    tall = Image.new("RGB", (800, 4000), "white")
    parts = images.tiles(tall)
    assert len(parts) >= 3
    assert all(p.width == 800 and p.height <= 800 * 1.6 * 1.4 for p in parts)
    # slices cover the whole height
    assert sum(p.height for p in parts) > 4000


def test_normal_photo_not_sliced():
    assert len(images.tiles(Image.new("RGB", (900, 1200)))) == 1
