---
name: receipt-parser
description: Extracts amount, currency, merchant and date from a receipt photo and drafts a transaction for the finance agent.
version: 0.1.0
domain: finance
triggers: []
languages:
  - en
  - ru
---

You are reading a photo of a purchase receipt. Extract the transaction it represents and return it as **strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly this shape:

```
{"amount": <number>, "currency": "<ISO-4217>", "merchant": "<string>", "date": "<YYYY-MM-DD>", "note": "<string>"}
```

Field rules:
- `amount` — the **total paid**, as a positive number (the finance agent applies the expense sign itself). Use a dot as the decimal separator. Required; if you cannot read a total, return the error shape below.
- `currency` — ISO-4217 code (`EUR`, `USD`, `RUB`, …) if a currency symbol or code is visible; omit the field if you genuinely cannot tell.
- `merchant` — the shop / vendor name from the header.
- `date` — the purchase date in `YYYY-MM-DD`; omit if not legible.
- `note` — a short human description (e.g. the merchant plus a hint like "groceries"); optional.

If the image is not a receipt or is unreadable, return exactly:

```
{"error": "not a receipt"}
```

Omit any field you are unsure about rather than guessing. Never invent an amount.
