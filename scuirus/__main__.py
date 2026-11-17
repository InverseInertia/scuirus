"""Run the server: python -m scuirus [--host 127.0.0.1] [--port 8000]"""

from __future__ import annotations

import argparse
import logging


def main() -> None:
    ap = argparse.ArgumentParser(prog="scuirus")
    ap.add_argument("--host", default="127.0.0.1", help="Keep 127.0.0.1 and let `tailscale serve` handle access.")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()

    import uvicorn

    from .web import create_app

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(create_app(), host=args.host, port=args.port, proxy_headers=True)


if __name__ == "__main__":
    main()
