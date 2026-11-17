"""Runs the app with a fake receipt reader for the browser test: python -m tests.e2e.serve DATA_DIR PORT"""

import sys
import time
from pathlib import Path

import uvicorn

from scuirus.config import Config
from scuirus.web import create_app
from tests.conftest import FakeReader


class SlowFakeReader(FakeReader):
    def read(self, photos):
        time.sleep(1.5)  # long enough for the UI to show "Reading…"
        return super().read(photos)


if __name__ == "__main__":
    data, port = Path(sys.argv[1]), int(sys.argv[2])
    app = create_app(Config(data_dir=data, reader="none"), reader=SlowFakeReader(),
                     lookup=lambda code: {"barcode": code, "name": "Acme Whole Milk", "pack_size": "1 gal"})
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")
