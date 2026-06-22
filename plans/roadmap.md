# Roadmap

## Stages
- **Stage 0 — foundation:** Maven parent + libs (contracts, mock-LLM). docker-compose (PG+pgvector+AGE, Radicale, MinIO, Langfuse). Liquibase skeleton + 001-core. profile-service + REST. `/start` via telegram-gateway. llm-gateway mock provider. orchestrator skeleton (echo agent). CI (build+test+docker image).
- **Stage 1 — calendar:** mcp-caldav + Radicale; create/list/update/delete/search. mcp-ics-import (hourly). calendar-agent + birthday-greeter. scheduler-service + proactive day-before-birthday trigger → notifier. gift-recommender skill. video-link-analyzer (mcp-youtube or web-search fallback). See calendar.md.
- **Stage 2 — finance:** finance schema + Liquibase 020. mcp-finance (CRUD + aggregates). mcp-money-pro-import (CSV + dry-run). finance-agent + transaction-categorizer. receipt-parser. budgets + budget-alerts. See finance.md.
- **Stage 3 — tasks (GTD):** tasks schema + mcp-tasks + tasks-agent. Inbox: anything not calendar/finance → tasks-inbox. "turn task into event" link with calendar.
- **Stage 4 — memory + inter-agent:** memory-service (pgvector recall, scope). AGE graph (Person/Place/Item nodes; likes/owns/related_to). LISTEN/NOTIFY bus in common + outbox in `bus`. First cross-agent chain: calendar.birthday_upcoming → creator.draft_greeting → notifier.send.
- **Stage 5 — real LLM:** llm-gateway providers Anthropic + DeepSeek. Langfuse traces. Golden tests on real models.
- **Stage 6+ — remaining agents:** chef → nutritionist → search → creator → stylist → briefing → ... Same structure each: schema (if needed) → MCP → skills → agent. **`nutrition` (food) IN PROGRESS** (owner-chosen after stylist; scope synced 2026-06-22): one `mcp-nutrition` domain-MCP (`nutrition` schema, port 8104) bound by **two MVP agents** — **`nutritionist-agent`** (food log + multi-person diet profiles + basket КБЖУ breakdown + ration + shopping list) + **`chef-agent`** (recipes: food.ru links + HTML card). Headline = the **grocery-receipt fan-out**: a recognised grocery receipt reaches finance (expense) **and** nutrition (basket breakdown) via the **event bus** (`basket.captured`), then nutritionist + chef propose a ration + recipes — multi-person incl. an infant on прикорм (with a pediatrician caveat). Food/macro data LOCKED = **Open Food Facts** (free, no key) via a shared `mcp-food-data` capability (sibling of market-data). The stylist render seam is lifted to a shared **`libs/doc-render`** here (the second-consumer rule). PR-sliced in [nutrition.md](nutrition.md). **`stylist` DONE through Phase 2** (owner-chosen 2026-06-21): MVP (ST-a..e) = `mcp-wardrobe` (port 8101) + `stylist-agent` (8102): wardrobe catalogue + "analyse me" style profile + capsule, all → **HTML** deliverables via a render seam (PDF deferred). **Phase 2 (ST-f..k)** = four **luxury-editorial boards** (Body & Style Analysis, Wardrobe Audit, Capsule, Gap Analysis) on a locked beige/Oranienbaum poster template (central photo anchor, hairline grid). **Image-gen line started (ST-l):** `mcp-image-gen` capability-MCP scaffolded with a **stub** engine — flip `IMAGE_GEN_ENGINE=local` to a self-hosted GPU model (owner's Mac Studio) later, no caller change; then bind it in stylist + wire the flows (board illustrations → virtual try-on). Still deferred: real generation engine + binding, marketplace buy-links, env-var theming, `outfit` catalogue (Fits-like, per-person). PR-sliced in [stylist.md](stylist.md).

## Candidate future agents (priority)
- tasks-agent (GTD) — high. health-agent (Apple Health) — med. docs-agent (receipts/contracts, OCR+search) — med. briefing-agent (morning digest: weather+calendar+finance+news) — high (wow). family-memory-agent — med. travel-agent — low. email-agent — low. smart-home-agent — if Home Assistant present.

## Candidate shared capability-MCPs (built when the first consumer needs them)
- **`chart-render`** — data → PNG/SVG for Telegram. First consumer: finance year-analysis charts; reused by briefing. Shared, not finance-specific.
- **`web-fetch/search`** (`mcp-web`) — `web_search` + `fetch_url` over **SearXNG** (self-hosted, free), cheap-first (HTTP retrieval → LLM-only-on-summary) for token economy. **In progress** (owner-chosen after finance MVP, 2026-06-20) — PR-sliced in [research.md](research.md), built alongside the `researcher` agent (its first consumer). Later bound by chef recipe search + briefing news. **`market-data`** (stocks/funds/metals/crypto quotes) is a **sibling** capability that rides in with finance investment-advisory — not part of `mcp-web` MVP. **In progress** (owner-chosen after the
researcher line, 2026-06-21): a `shared/mcp/mcp-market-data` capability-MCP (`quote` over **Stooq** —
LOCKED, free/no-key) + a finance `investment-advisor` skill (**advisory-only**), PR-sliced in
[market-data.md](market-data.md).
- **`mcp-media-processing` STT** (whisper, MP-d2) — ✅ **DONE** (PR123/124): `transcribe` over a whisper
  ASR sidecar; voice → text for any agent (finance voice capture, docs, …).
- **`mcp-image-gen`** — `generate_image(prompt, refMediaIds?)` → store in media-service → media id.
  **Scaffolded (ST-l, PR144)** with a **stub** engine behind an `ImageEngine` seam; flips to a
  self-hosted local GPU model (owner's Mac Studio) by config (`IMAGE_GEN_ENGINE=local`). First
  consumer: stylist (board illustrations, then virtual try-on via CatVTON/IDM-VTON with `refMediaIds`);
  reusable by creator/briefing later. Real engine + stylist binding + flow wiring still to come.

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

## Evaluated tools (owner-shared 2026-06-20) — integrate over HTTP/MCP, do not rewrite
Per the **polyglot-by-design** doctrine ([architecture.md](architecture.md) §Principles): run each
as its own service and bind a thin capability-MCP. Language of the upstream project doesn't matter.

| Tool | Verdict | Fit | Note |
|---|---|---|---|
| [Agent-Reach](https://github.com/Panniantong/Agent-Reach) | 🟢 strong (35.5k★, MIT, active) | researcher **extension** | Python CLI "capability layer" w/ fallbacks: YouTube/Twitter/Reddit/Bilibili/**RSS** + **video transcripts**. Closes the deferred video/social slice. But CLI-oriented, pulls external svcs (Jina/Exa) + cookie auth. Take its *tools* (yt-dlp, RSS) as a follow-up `mcp-web` extension — don't replace the clean self-hosted SearXNG core. |
| [Whisper](https://github.com/openai/whisper) | 🟢 adopt | MP-d2 (STT) | Already planned. Use faster-whisper/whisper.cpp for speed; run as a service behind `mcp-media-processing`'s `transcribe`. |
| [CatVTON](https://github.com/Zheng-Chong/CatVTON) | 🟢 good | stylist (virtual try-on) | Python/diffusers, ICLR 2025, <8GB VRAM, self-host (Gradio/ComfyUI). **License CC-BY-NC-SA (non-commercial)** — fine for family. ⚠️ CUDA → on Apple-Silicon Mac Studio runs via MPS, not turnkey. |
| [Fooocus](https://github.com/lllyasviel/Fooocus) | 🟢 good | stylist (image-gen) — **engine candidate for `mcp-image-gen` `local`** | SDXL gen by the ControlNet author, free/local. Needs API mode / ComfyUI for programmatic calls (Fooocus itself is a UI). Same Apple-Silicon GPU caveat. The `mcp-image-gen` `LocalImageEngine` seam (ST-l) is where it plugs in. |
| [Grafana](https://github.com/grafana/grafana) | 🟢 adopt | finance dashboards | Already in architecture (over `finance.*` + matviews). Run it, zero code. |
| [pinterest-mcp-server](https://github.com/Fydel-Tools/pinterest-mcp-server) | 🔴 skip for now | stylist (inspiration) | TS, official Pinterest API — but **0★, 1 commit**, immature; and it's about *managing* your account (posting pins), not harvesting inspiration. Official-API search is weak. Find inspiration-harvesting another way. |
| [AppFlowy](https://github.com/AppFlowy-IO/AppFlowy) | 🟡 caution | "visual digest" | Full Notion-alt (Rust+Flutter); programmatic doc-gen immature → overkill for a digest. Prefer Grafana / generated HTML-PDF. Adopt only for a full personal-wiki workspace. |

**Live-verified (2026-06-20):** `docker compose up searxng mcp-web` → real SearXNG returns live hits
(incl. YouTube links) and `fetch_url` extracts real article text; the full researcher chain runs to
a (mock-LLM) synthesis. **Finding:** JS-rendered pages (YouTube) yield only boilerplate via jsoup —
confirms the **video-transcript follow-up** (yt-dlp / Agent-Reach) is genuinely needed for video content.

## Risks
- Spring AI MCP young — may need own client; keep behind interface in libs/mcp-client.
- Apache AGE Docker build sometimes unstable — plan B: Neo4j Community.
- Radicale + Apple Calendar — aggressive subscription caching; verify interval.
- Money Pro CSV format varies by version — tolerant parser with preview.
- Mock-LLM determinism must not hide real bugs — golden-test output structure, not text.

## MVP verification
docker-compose up brings up all infra. `mvn verify` green. Bot answers `/start` (registers core.users). "запиши ДР Маши 15 июля" → event in Radicale, visible in Apple Calendar via subscription. Voice "потратил 1500 в Ленте на еду" → tx in finance.transactions, category Еда. Money Pro CSV: dry-run preview → confirm → history in DB, idempotent on re-run. Day before birthday → Telegram message with gift suggestion. Langfuse shows LLM traces (once real LLM connected).
