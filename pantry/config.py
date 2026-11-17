"""Settings from pantry.toml (optional) with environment-variable overrides."""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Config:
    data_dir: Path = Path("data")
    reader: str = "claude"  # claude | ollama | none
    claude_model: str = "claude-opus-5-5"
    claude_effort: str = "high"
    claude_api_key: str | None = None
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen2.5vl:7b"
    off_contact: str | None = None

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        path = path or Path(os.environ.get("PANTRY_CONFIG", "pantry.toml"))
        c = cls()
        if path.exists():
            t = tomllib.loads(path.read_text())
            c.data_dir = Path(t.get("data_dir", c.data_dir))
            r = t.get("receipts", {})
            c.reader = r.get("reader", c.reader)
            cl = t.get("claude", {})
            c.claude_model = cl.get("model", c.claude_model)
            c.claude_effort = cl.get("effort", c.claude_effort)
            c.claude_api_key = cl.get("api_key") or None
            ol = t.get("ollama", {})
            c.ollama_url = ol.get("url", c.ollama_url)
            c.ollama_model = ol.get("model", c.ollama_model)
            c.off_contact = t.get("openfoodfacts", {}).get("contact") or None
        env = os.environ
        c.data_dir = Path(env.get("PANTRY_DATA_DIR", c.data_dir))
        c.reader = env.get("PANTRY_READER", c.reader)
        c.claude_model = env.get("PANTRY_CLAUDE_MODEL", c.claude_model)
        c.ollama_url = env.get("PANTRY_OLLAMA_URL", c.ollama_url)
        c.ollama_model = env.get("PANTRY_OLLAMA_MODEL", c.ollama_model)
        c.off_contact = env.get("OFF_CONTACT", c.off_contact)
        return c
