"""Suggests catalog products for a receipt line. The user always confirms the match."""

from __future__ import annotations

import re

from .domain import Product

ABBREVIATIONS = {
    "org": "organic", "orgnc": "organic", "bnls": "boneless", "sknls": "skinless", "chkn": "chicken",
    "brst": "breast", "bf": "beef", "grnd": "ground", "whl": "whole", "wht": "white", "wh": "white",
    "blk": "black", "grn": "green", "ylw": "yellow", "rd": "red", "chs": "cheese", "chse": "cheese",
    "yog": "yogurt", "yogrt": "yogurt", "bnna": "banana", "bananas": "banana", "tom": "tomato", "tomatoes": "tomato",
    "pot": "potato", "potatoes": "potato", "brd": "bread", "mlk": "milk", "btr": "butter",
    "veg": "vegetable", "fz": "frozen", "frz": "frozen", "gal": "gallon", "pk": "pack", "eggs": "egg",
    "apples": "apple", "onions": "onion", "carrots": "carrot", "peppers": "pepper",
}

_non_word = re.compile(r"[^a-z0-9 ]")


def tokens(text: str) -> list[str]:
    words = _non_word.sub(" ", text.lower()).split()
    return [ABBREVIATIONS.get(w, w) for w in words if not w.isdigit()]


def normalize(text: str) -> str:
    """The key aliases are stored under: lowercase words, numbers dropped, abbreviations expanded."""
    return " ".join(tokens(text))


def suggest(raw_text: str, name: str | None, products: list[Product], aliases: dict[str, str],
            limit: int = 3) -> list[dict]:
    """Ranks products for one receipt line.

    A learned alias for the printed text wins outright. Otherwise products are scored by word overlap
    with both the printed text and the model's expanded name, weighting coverage of the product name,
    since receipts drop words.
    """
    by_id = {p.id: p for p in products}
    for key in (normalize(raw_text), normalize(name or "")):
        if key and key in aliases and aliases[key] in by_id:
            return [{"product": by_id[aliases[key]], "score": 1.0, "alias": True}]

    query = set(tokens(raw_text)) | set(tokens(name or ""))
    if not query:
        return []
    scored = []
    for p in products:
        pt = set(tokens(p.name))
        if not pt:
            continue
        inter = len(query & pt)
        if not inter:
            continue
        score = 0.4 * inter / len(query | pt) + 0.6 * inter / len(pt)
        if score >= 0.34:
            scored.append({"product": p, "score": round(score, 3), "alias": False})
    scored.sort(key=lambda s: s["score"], reverse=True)
    return scored[:limit]
