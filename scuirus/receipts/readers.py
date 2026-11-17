"""Receipt readers. Each turns receipt photos into a `Receipt`.

claude  Anthropic's API. The most accurate option. Sends the photos to Anthropic.
ollama  A vision model on your own machine through Ollama. Nothing leaves the house; accuracy depends
        on the model and how much GPU memory it gets.
"""

from __future__ import annotations

import base64
import logging
from typing import Protocol

import httpx
from PIL import Image

from .. import images as imgs
from .schema import INSTRUCTIONS, Receipt

log = logging.getLogger(__name__)


class ReaderError(Exception):
    pass


class Reader(Protocol):
    name: str

    def read(self, photos: list[Image.Image]) -> Receipt: ...


def prepare(photos: list[Image.Image], max_side: int) -> list[bytes]:
    """Slices tall photos and shrinks every slice to what the model will actually look at."""
    out = []
    for photo in photos:
        for tile in imgs.tiles(photo):
            out.append(imgs.to_jpeg(imgs.fit(tile, max_side), quality=88))
    return out


class ClaudeReader:
    name = "claude"

    def __init__(self, model: str = "claude-opus-5-5", effort: str = "high", api_key: str | None = None,
                 client=None, max_side: int = 1568):
        self.model = model
        self.effort = effort
        self.max_side = max_side
        if client is None:
            import anthropic

            client = anthropic.Anthropic(api_key=api_key) if api_key else anthropic.Anthropic()
        self.client = client

    def read(self, photos: list[Image.Image]) -> Receipt:
        import anthropic

        content: list[dict] = []
        for jpeg in prepare(photos, self.max_side):
            content.append({
                "type": "image",
                "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.standard_b64encode(jpeg).decode()},
            })
        content.append({"type": "text", "text": "Read this receipt."})
        try:
            response = self.client.beta.messages.parse(
                model=self.model,
                max_tokens=16000,
                system=INSTRUCTIONS,
                messages=[{"role": "user", "content": content}],
                output_format=Receipt,
                output_config={"effort": self.effort},
                # If a safety classifier declines, the API retries on a fallback model in the same call.
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )
        except anthropic.AuthenticationError as e:
            raise ReaderError("Anthropic API key missing or invalid. Set ANTHROPIC_API_KEY or [claude] api_key.") from e
        except anthropic.RateLimitError as e:
            raise ReaderError("Anthropic rate limit hit. Try again in a minute.") from e
        except anthropic.APIStatusError as e:
            raise ReaderError(f"Anthropic API error {e.status_code}: {e.message}") from e
        except anthropic.APIConnectionError as e:
            raise ReaderError("Could not reach the Anthropic API.") from e

        if response.stop_reason == "refusal":
            raise ReaderError("The model declined to read this image.")
        if response.stop_reason == "max_tokens":
            raise ReaderError("The receipt was too long to read in one go. Try splitting it into two photos.")
        if response.parsed_output is None:
            raise ReaderError("The model returned no structured result.")
        return response.parsed_output


class OllamaReader:
    name = "ollama"

    def __init__(self, url: str = "http://127.0.0.1:11434", model: str = "qwen2.5vl:7b", timeout: float = 600,
                 max_side: int = 1280, transport: httpx.BaseTransport | None = None):
        self.url = url.rstrip("/")
        self.model = model
        self.max_side = max_side
        self.http = httpx.Client(timeout=timeout, transport=transport)

    def read(self, photos: list[Image.Image]) -> Receipt:
        body = {
            "model": self.model,
            "stream": False,
            "format": Receipt.model_json_schema(),
            "options": {"temperature": 0},
            "messages": [
                {"role": "system", "content": INSTRUCTIONS},
                {"role": "user", "content": "Read this receipt. Answer with JSON only.",
                 "images": [base64.b64encode(j).decode() for j in prepare(photos, self.max_side)]},
            ],
        }
        try:
            r = self.http.post(f"{self.url}/api/chat", json=body)
        except httpx.HTTPError as e:
            raise ReaderError(f"Could not reach Ollama at {self.url}: {e}") from e
        if r.status_code != 200:
            raise ReaderError(f"Ollama returned {r.status_code}: {r.text[:300]}")
        try:
            text = r.json()["message"]["content"]
            return Receipt.model_validate_json(text)
        except Exception as e:
            raise ReaderError(f"Ollama's answer was not a valid receipt: {e}") from e
