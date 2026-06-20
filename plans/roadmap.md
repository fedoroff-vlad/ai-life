# Roadmap

## Stages
- **Stage 0 — foundation:** Maven parent + libs (contracts, mock-LLM). docker-compose (PG+pgvector+AGE, Radicale, MinIO, Langfuse). Liquibase skeleton + 001-core. profile-service + REST. `/start` via telegram-gateway. llm-gateway mock provider. orchestrator skeleton (echo agent). CI (build+test+docker image).
- **Stage 1 — calendar:** mcp-caldav + Radicale; create/list/update/delete/search. mcp-ics-import (hourly). calendar-agent + birthday-greeter. scheduler-service + proactive day-before-birthday trigger → notifier. gift-recommender skill. video-link-analyzer (mcp-youtube or web-search fallback). See calendar.md.
- **Stage 2 — finance:** finance schema + Liquibase 020. mcp-finance (CRUD + aggregates). mcp-money-pro-import (CSV + dry-run). finance-agent + transaction-categorizer. receipt-parser. budgets + budget-alerts. See finance.md.
- **Stage 3 — tasks (GTD):** tasks schema + mcp-tasks + tasks-agent. Inbox: anything not calendar/finance → tasks-inbox. "turn task into event" link with calendar.
- **Stage 4 — memory + inter-agent:** memory-service (pgvector recall, scope). AGE graph (Person/Place/Item nodes; likes/owns/related_to). LISTEN/NOTIFY bus in common + outbox in `bus`. First cross-agent chain: calendar.birthday_upcoming → creator.draft_greeting → notifier.send.
- **Stage 5 — real LLM:** llm-gateway providers Anthropic + DeepSeek. Langfuse traces. Golden tests on real models.
- **Stage 6+ — remaining agents:** chef → nutritionist → search → creator → stylist → briefing → ... Same structure each: schema (if needed) → MCP → skills → agent.

## Candidate future agents (priority)
- tasks-agent (GTD) — high. health-agent (Apple Health) — med. docs-agent (receipts/contracts, OCR+search) — med. briefing-agent (morning digest: weather+calendar+finance+news) — high (wow). family-memory-agent — med. travel-agent — low. email-agent — low. smart-home-agent — if Home Assistant present.

## Candidate shared capability-MCPs (built when the first consumer needs them)
- **`chart-render`** — data → PNG/SVG for Telegram. First consumer: finance year-analysis charts; reused by briefing. Shared, not finance-specific.
- **`web/market-data` + `web-fetch/search`** — quotes (stocks/funds/metals/crypto) + news/article/video fetch with a cheap-first (API/scrape) → LLM-only-on-summary posture (token economy). First consumers: `researcher`, finance investment-advisory, chef recipe search, briefing news.
- **`mcp-media-processing` STT** (whisper, MP-d2) — voice → text for any agent (finance voice capture, docs, …). Engine slice like OCR.

## Finance vision beyond MVP (owner 2026-06-20 — detail in [finance.md](finance.md))
MVP now = receipt→capture + confirm + spending **analysis**. Recorded-but-later, each on an existing
substrate (no new layer): year analysis **with chart** (→ `chart-render`), a designed **report template**,
chat-driven **category grouping**, **big-purchase deliberation** (Coordinator + conversation-state),
**voice capture** (STT), and **investment advisory** (stocks/funds/metals/crypto → ideas only).
**Investment advisory is advisory-only — it never executes trades or moves money**, and waits on the
shared `web/market-data` capability (so it rides in with the `researcher`).

## n8n variant (parallel from Stage 1)
`n8n/workflows/<agent>/*.json` — same logic without Java, for prototyping/sharing/lightweight integrations (e.g. RSS→LLM filter→Telegram). Each scenario in two variants (Java + n8n) to compare.

## Open-source reuse (don't rebuild what exists)
Caldav: Radicale, ical4j, (CalDAV4j only if PROPFIND/REPORT needed). Telegram: TelegramBots Java + spring-boot-starter. Scheduling: ShedLock. MCP catalogs: modelcontextprotocol/servers (official: filesystem, github, postgres, fetch, brave-search, memory), punkpeye/awesome-mcp-servers, smithery.ai, mcp.so. Reusable per agent: mcp-youtube-transcript (calendar video), official postgres MCP (finance base), mcp-fetch/brave/tavily/perplexity (search), mcp-rss/reddit, mcp-pinterest (stylist), mcp-ffmpeg / imagemagick (creator), mcp-whisper + Tesseract (gateway media). Finance alt considered & rejected: Firefly III / Actual / maybe-finance. Memory refs: official memory-server, mem0, Letta. Observability: Langfuse, OpenTelemetry.

**Owner principle (2026-06-19):** reuse free OSS libs over rebuilding, and make broadly-useful capabilities **shared** (a capability-MCP any agent binds), not embedded in one agent — because a photo/audio/video can belong to any domain. **Next shared capability (after D3): `mcp-media-processing`** — OCR (Tesseract/PaddleOCR/docTR) + STT (whisper) + vision-caption over media-service bytes, applied around routing so any agent (finance receipts, docs sick-notes, stylist outfits…) reuses it. Migrate the interim `receipt-parser` vision-in-agent shortcut onto it. **PR-sliced in [media.md](media.md)** (MP-a scaffold+ocr-stub → MP-b real Tesseract → MP-c bind finance-agent + migrate receipt-parser → MP-d caption/STT). See also STATUS Deferred work.

## Risks
- Spring AI MCP young — may need own client; keep behind interface in libs/mcp-client.
- Apache AGE Docker build sometimes unstable — plan B: Neo4j Community.
- Radicale + Apple Calendar — aggressive subscription caching; verify interval.
- Money Pro CSV format varies by version — tolerant parser with preview.
- Mock-LLM determinism must not hide real bugs — golden-test output structure, not text.

## MVP verification
docker-compose up brings up all infra. `mvn verify` green. Bot answers `/start` (registers core.users). "запиши ДР Маши 15 июля" → event in Radicale, visible in Apple Calendar via subscription. Voice "потратил 1500 в Ленте на еду" → tx in finance.transactions, category Еда. Money Pro CSV: dry-run preview → confirm → history in DB, idempotent on re-run. Day before birthday → Telegram message with gift suggestion. Langfuse shows LLM traces (once real LLM connected).
