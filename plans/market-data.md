# market-data — quotes capability + finance investment advisory

Authority file for the shared **`mcp-market-data` capability-MCP** (stocks / funds / metals /
crypto quotes) and the finance **`investment-advisor`** skill that binds it. Owner-chosen after
the `researcher` line (2026-06-21); the sibling of `mcp-web` named in `roadmap.md` and
`finance.md`'s recorded vision.

## Advisory-only — the hard constraint (never relitigated)
The `investment-advisor` skill is **advisory only — it never executes a trade, places an order,
moves money, or initiates a transfer.** It reads quotes, reasons over them + the user's stated
holdings/watchlist, and returns *ideas/considerations the user decides on*. This mirrors the
computer-use financial-actions rule and `finance.md`'s recorded vision. `mcp-market-data` is a
**read-only** quotes source — no order/account endpoints exist or will.

## Why these two things (doctrine, not relitigated)
`architecture.md`: a raw external capability → a **capability-MCP** any agent binds; reasoning over
it → a domain agent's skill. So market access splits in two:
- **`mcp-market-data`** (`shared/mcp/`, no schema) — the shared read-only quotes toolbox: `quote`
  (latest) + later `history` (series for trend). A **sibling** of `mcp-web` (not part of it) —
  market data is structured numeric reads, not web search. Reusable by briefing (a "markets" line),
  not just finance.
- **`investment-advisor`** skill in **finance-agent** — turns "что думаешь про мои акции X, Y?" into
  a gather (quote the named symbols) → LLM synthesis of advisory considerations, advisory-only.

## Decision — quotes source: **Stooq** (LOCKED, owner 2026-06-21)
`stooq.com` CSV endpoints: **free, no API key, no quota signup, no secret to manage** — the same
free/no-key/simple principle that picked SearXNG (mcp-web) and whisper (STT). Covers world
stocks / indices / ETFs(funds) / metals (e.g. `xauusd` gold) / forex and some crypto. Behind a
`MarketDataSource` interface (`engine/MarketDataSource`, mirrors `SearchEngine`/`SttEngine`) selected
by `marketdata.source` (`stooq` default) — so Yahoo Finance or a keyed provider (Alpha Vantage /
Finnhub) can replace it later via env with **no caller change**. Trade-offs accepted: Stooq is
unofficial and its crypto coverage is partial — a sibling source can fill gaps behind the same
interface later. Symbol mapping (a ticker → a Stooq symbol like `aapl.us` / `^spx` / `xauusd`) is the
**caller's** job (the skill); the capability is dumb — symbol in, quote out.

## Shape
- `shared/mcp/mcp-market-data` — capability-MCP, **port 8100**, no DB. Talks to **Stooq** over HTTPS
  (`MARKETDATA_STOOQ_URL`, default `https://stooq.com`). Tools `quote(symbol)` (+ later
  `history(symbol, range?)`), each also an HTTP `/internal/*` passthrough (the deterministic,
  MockWebServer-testable path an agent calls; MCP/SSE can't be mocked — same doctrine as
  `mcp-media-processing` / `mcp-web`). Template: `shared/mcp/mcp-web`.
- `finance-agent` binds `mcp-market-data` (SSE for future LLM tool-selection + an HTTP client for the
  deterministic flow) — mirror how it binds `mcp-media-processing` (MP-c2). One `investment-advisor`
  skill on the shared `Coordinator`. Routed via `IntentRouter` (a new `invest`/advisory action,
  alongside the existing spending-`advice`).
- Contracts in `libs/contracts/.../market/`: `QuoteInput(symbol)`,
  `Quote(symbol, price, asOf, currency?, change?, changePercent?)` (fields the source actually
  yields; absent ones stay null).

## PR-sized slices
- **MD-0 — docs opener (this).** `market-data.md` + INDEX row + `roadmap.md` (mark in-progress,
  Stooq locked) + `finance.md` (investment-advisory row: source locked) + STATUS. No code.
- **MD-a — `mcp-market-data` scaffold + `quote` over Stooq.** ✅ **DONE (PR126).** New module
  (no JPA, port 8100, no backing container — Stooq is public HTTPS), `engine/MarketDataSource` +
  `StooqMarketDataSource` (`@ConditionalOnProperty marketdata.source=stooq`, matchIfMissing; GET Stooq
  `/q/l/?s={symbol}&f=sd2t2ohlcv&e=csv` → parse the CSV row; `N/D`→null fields = "no data", not an
  error), `quote(symbol)` `@Tool`, `POST /internal/quote` passthrough, `market/Quote` +
  `market/QuoteInput` contracts. Read-only — **no order/trade tool, by design**. `InternalQuoteControllerTest`
  (MockWebServer for Stooq: parses a real row + unknown-symbol→null-price; no network). Registered in
  root pom + compose + `.env.example` + infra/README. **No agent binding yet** — the capability stands
  alone and is fully testable.
- **MD-b (optional, later) — `history(symbol, range?)`.** Stooq's daily-series CSV
  (`/q/d/l/?s={symbol}&i=d`) → a compact series for trend/% change. Add only when the advisor needs
  more than a spot quote.
- **MD-c — finance-agent binds `mcp-market-data` + `investment-advisor` skill.** `flow/InvestmentAdvisor`
  on the `Coordinator` (copy `FinancialAdvisor`): gather a `quote` per symbol the user named (parallel,
  soft-fail per symbol) → one LLM synthesis from `[AGENT.md, investment-advisor SKILL.md] +
  {payload(userText, symbols), context(quotes)}` → considerations, **advisory-only** (the SKILL.md
  states the no-trade rule loudly). New `http/MarketDataClient` (`/internal/quote`) + the SSE binding +
  `MARKET_DATA_URL`. Routed via a new advisory action in `IntentRouter`; `skills/finance/investment-advisor/SKILL.md`.
  `InvestmentAdvisorTest` (MockWebServers for `/internal/quote` + llm-gateway).

## Out of scope (recorded, later)
- **Holdings/portfolio storage** — MVP reads symbols the user names in the message (or a future thin
  watchlist). A persisted portfolio (cost basis, P/L) is a finance-schema follow-up, not this line.
- **News/analysis fetch** — that's `mcp-web` (the researcher's capability); the advisor can later
  gather a `web_search` alongside quotes. Not MD MVP.
- **Real LLM synthesis quality** — Stage 5 (mock LLM proves the wiring; the flow is model-agnostic).
- **Any execution / brokerage integration** — permanently out of scope (advisory-only).
