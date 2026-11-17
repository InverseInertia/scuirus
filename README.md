# Pantry

A self-hosted grocery tracker that runs on a home server and opens in the phone's browser over Tailscale. You add stock by scanning barcodes or importing receipt photos, and remove it by scanning or with one-tap buttons. The server does all the work: it decodes barcodes, reads receipts with a vision model, and stores everything in SQLite. The phone only displays the pages.

This replaces the earlier Android app, which is still in git history (commit `9201d21`).

## How it fits together

```
phone browser ──https──> tailscale serve ──> 127.0.0.1:8000  python -m pantry
                                                              ├─ SQLite: products, events, aliases, receipts
                                                              ├─ zxing-cpp: barcode decoding
                                                              └─ receipt reader: Claude API or Ollama
```

```
pantry/
  domain.py        events, replay, stats (exact fractions)
  store.py         SQLite
  service.py       every stock change goes through here
  barcode.py       server-side decoding
  matcher.py       receipt line -> catalog product, plus learned aliases
  receipts/        reader backends, schema, upload pipeline
  web.py           HTTP API, serves static/
  static/          the frontend: plain HTML/CSS/JS, no build step
tests/             pytest suite and a browser test (tests/e2e)
deploy/            systemd unit for distrobox
```

## Running it on the server (Bazzite + distrobox)

Inside a distrobox with Python 3.11 or newer (any Fedora or Ubuntu box works):

```bash
distrobox create --name pantry --image registry.fedoraproject.org/fedora-toolbox:41
```

```bash
distrobox enter pantry
```

```bash
cd ~/scuirus && python3 -m venv .venv && .venv/bin/pip install -e .
```

```bash
cp pantry.example.toml pantry.toml
```

Edit `pantry.toml` (receipt reader, API key or Ollama URL, Open Food Facts contact), then start it:

```bash
.venv/bin/python -m pantry
```

It listens on `127.0.0.1:8000` only. To run it as a service that starts on boot, use `deploy/pantry.service`; the install commands are at the top of that file.

### Reaching it from the phone

Run this on the Bazzite host, where Tailscale runs:

```bash
sudo tailscale serve --bg 8000
```

The app is then at `https://<machine-name>.<tailnet>.ts.net` on every device in your tailnet. HTTPS matters: browsers only allow the live camera on secure origins. MagicDNS and HTTPS certificates must be turned on in the Tailscale admin console under DNS. Without HTTPS the Photo button and receipt import still work, because they use the phone's file picker instead of the live camera.

In Chrome on Android, the menu offers Add to Home screen (or Install app) to open it like an app.

## Receipt reading

Receipts are read by a vision model that returns structured data: store, date, each line with the printed text, a readable name, quantity and unit, price after discounts, and whether it is food. Tall receipt photos are cut into overlapping slices first, so small print survives the model's image downscaling. Before you add anything, the review screen compares the lines against the printed subtotal, so a missed or misread line shows up as a warning.

Two backends, chosen in `pantry.toml`:

- **`claude`** (default): Anthropic's API with `claude-opus-5-5`. The most accurate option, and it copes with crumpled, angled and faded receipts. Photos are sent to Anthropic. Set `ANTHROPIC_API_KEY` or `[claude] api_key`. Expect a few cents per receipt at `effort = "high"`. It enables Anthropic's server-side refusal fallback, so in the unlikely case the main model declines an image, the request is retried on a fallback model instead of failing.
- **`ollama`**: a local vision model through [Ollama](https://ollama.com), so nothing leaves the house. Pull a vision model first, for example `ollama pull qwen2.5vl:7b`. Accuracy depends on the model size and GPU memory. Expect it to need more corrections than Claude, especially on long receipts.

Each reading is matched against your catalog. When you confirm a match, the printed text (`ORG WHL MLK 1GAL`) is saved as an alias, and the next receipt with the same text matches automatically.

### Importing from the phone

On the Receipts tab, Import receipt photos opens the phone's picker, where you can select several photos at once from a receipts folder. Each photo is treated as its own receipt unless you tick "These photos are parts of one long receipt". Photos already imported are skipped, so you can re-select the whole folder each time. Reading runs in the background one receipt at a time. The list refreshes itself, and each receipt shows Review when it's ready.

## Barcodes

The Scan tab streams camera frames to the server about three times a second, and the server decodes them with zxing-cpp. Use up removes the product's "uses per scan" amount (for example 1/4 of a gallon) from the lot that expires first. Add adds one unit. An unknown barcode opens the new-product form, pre-filled from Open Food Facts when they know the code. Holding a code in view does not repeat the action. Move it out of view for a few seconds to scan the same item again.

## Data

- Stock is never stored. It is rebuilt from an append-only event log (`add`, `consume`, `discard`, `adjust`, `void`), so the stock can't drift from the history. The History tab shows the log.
- Quantities are exact fractions, so three removals of 1/3 empty a lot exactly.
- Removing more than you have is capped at what's in stock. It never goes negative.
- "Added by mistake" on a lot voids it, which removes it from stock and spending stats while keeping the history.
- Everything lives in `data/`: `pantry.sqlite3` plus `receipts/<id>/*.jpg` (the receipt photos are kept). Back up that folder.

## Tests

```bash
.venv/bin/pip install -e '.[test]' && .venv/bin/python -m pytest
```

The browser test needs Node with Playwright and ffmpeg. It starts the server with a fake receipt reader, opens Chromium at a Pixel 7 size with a fake camera that shows a barcode, and goes through creating a product by scan, use-up scans, a typed barcode, a receipt import and review, the stock list, and history:

```bash
node tests/e2e/phone.test.mjs
```

## What was verified

- 42 pytest tests: replay and stats, service operations, persistence across restarts, barcode decoding of generated UPC-A/EAN-13 images (including rotated), EXIF rotation, receipt slicing, the full upload → read → review → commit flow, duplicate-photo skipping, reader errors and retry, and the HTTP API.
- The Claude and Ollama readers are tested against mocked HTTP. The tests check the exact request each one sends (model, structured-output schema, image slices, fallback header) and how replies, refusals and malformed answers are handled.
- The browser test passes in Chromium at phone size, with no console errors.

## What was not verified

- **Neither receipt reader has run against a real model.** No API key or GPU was available. The first real receipts will show how good the extraction is, and the prompt in `pantry/receipts/schema.py` is the place to tune it.
- No real phone camera or real receipt photos. The fake camera shows a clean, flat barcode.
- `tailscale serve` and the systemd unit were written from documentation, not run on a Bazzite machine.
- The Open Food Facts lookup was tested against a hand-written reply, not the live API.

## Known gaps

- The app needs a connection to the server. Receipt photos taken offline can be imported later, but scanning and stock changes can't be queued offline.
- No login. Anyone on your tailnet can use it, which is usually just you.
- An `adjust` can set a new expiry but can't clear one.
- No charts or trends screen. Price, usage rate and waste share appear on each product card.
