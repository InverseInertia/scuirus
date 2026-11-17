"""What a receipt reader must return, and the instructions every backend gets."""

from __future__ import annotations

from pydantic import BaseModel, Field


class ReceiptItem(BaseModel):
    raw_text: str = Field(description="The item text exactly as printed, abbreviations and codes included.")
    name: str = Field(description="A plain readable product name, abbreviations expanded, e.g. 'Organic whole milk'.")
    quantity: float = Field(description="How many units, or the weight for items sold by weight. 1 if not shown.")
    unit: str = Field(description="'each' for counted items, or the weight unit printed: lb, kg, oz, g.")
    line_total: float | None = Field(description="What was paid for this line after any discount on it. Null if unreadable.")
    discount: float | None = Field(description="Total discount applied to this line as a positive number, or null.")
    is_food: bool = Field(description="False for bags, deposits, gift cards, household goods and other non-groceries.")
    category: str | None = Field(description="One of: produce, dairy, meat, seafood, bakery, frozen, pantry, beverages, snacks, household, other.")


class Receipt(BaseModel):
    store: str | None = Field(description="Store name as printed at the top, or null.")
    date: str | None = Field(description="Purchase date as YYYY-MM-DD, or null if not printed.")
    items: list[ReceiptItem]
    subtotal: float | None = Field(description="Printed subtotal before tax, or null.")
    tax: float | None = Field(description="Total tax, or null.")
    total: float | None = Field(description="Printed grand total, or null.")
    notes: str | None = Field(description="Anything that was hard to read or that the user should double-check, or null.")


INSTRUCTIONS = """You are reading a photo of a grocery store receipt so the items can be added to a home pantry tracker.

Return every purchased item line. Rules:
- One entry per purchased line, in printed order. If the same product appears on two lines, return two entries.
- Weight lines such as "1.42 lb @ 2.99 /lb" belong to the item above them: set quantity 1.42, unit "lb".
- Lines like "3 @ 1.50" or "2 x" set the quantity of the item they belong to.
- Coupon, discount and savings lines are not items. Subtract them from the item they apply to,
  record the amount in that item's discount, and put the discounted price in line_total.
- Skip subtotal, tax, total, payment, change, loyalty, and survey lines.
- Keep raw_text exactly as printed so it can be matched against what was learned before.
- Expand abbreviations in name using your knowledge of grocery receipts (e.g. "ORG BNLS CHKN BRST" -> "Organic boneless chicken breast").
- Never invent an item or a price. If a price is unreadable, use null and mention it in notes.
- Use null for any receipt-level field that is not printed.

When several images are given they are consecutive, overlapping slices of one long receipt, top to bottom.
A line cut off or repeated where two slices overlap is the same printed line: return it once."""
