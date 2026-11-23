"""Writes the fake camera feed (a barcode in front of the lens) and a receipt photo for the browser test."""

import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

from tests.conftest import barcode_png

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)

import io

code = Image.open(io.BytesIO(barcode_png("036000291452", scale=3, pad=10)))
frame = Image.new("RGB", (1280, 720), (120, 110, 100))
frame.paste(code, ((1280 - code.width) // 2, (720 - code.height) // 2))
frame.save(out / "frame.png")
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-loop", "1", "-i", str(out / "frame.png"), "-t", "3",
                "-r", "10", "-pix_fmt", "yuv420p", str(out / "camera.y4m")], check=True)

receipt = Image.new("RGB", (700, 1400), "white")
d = ImageDraw.Draw(receipt)
for i, line in enumerate(["CORNER GROCER", "ORG WHL MLK 1GAL   5.49", "BANANAS 2.31 lb   1.36", "BNLS CHKN BRST  9.12",
                          "PAPER BAG   0.10", "SUBTOTAL 16.07", "TAX 0.01", "TOTAL 16.08"]):
    d.text((40, 60 + i * 50), line, fill="black")
receipt.save(out / "receipt.jpg", quality=90)
print(out)
