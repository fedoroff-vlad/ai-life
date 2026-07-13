# infra

Local development infrastructure + full-system compose for ai-life.

## Two compose files — pick the right one

| file | covers | when |
|---|---|---|
| `docker-compose.dev.yml` | postgres + liquibase + radicale + minio + searxng + whisper + grafana (backing services only, no app services) | IDE-driven development. Run JVMs from IntelliJ pointed at host ports. |
| `docker-compose.yml` | everything in `dev.yml` **plus** the full app stack — all platform services, domain agents, and MCP servers (see the port table below for the current set) | End-to-end smoke testing, Mac Studio deployment, validating a release. |

Both files share `.env` and the same Postgres volume — they don't conflict, but
don't run them at the same time either (port collisions on 5432 / 5232).

## Start (IDE-driven dev)

```sh
cd infra
cp .env.example .env   # edit if you want non-default ports/passwords
docker compose -f docker-compose.dev.yml up -d
```

Liquibase runs automatically after Postgres becomes healthy and applies
`liquibase/db.changelog-master.xml`. The `liquibase` container exits with code 0 once
migrations are done — that is expected.

## Start (full system)

```sh
cd infra
cp .env.example .env
docker compose -f docker-compose.yml up -d --build   # first run builds every app image
docker compose -f docker-compose.yml logs -f gateway-telegram
```

First build is ~5–10 minutes (one Maven build per Dockerfile; `--mount=type=cache`
keeps `~/.m2` warm between subsequent builds). Subsequent `up` calls are fast.

## Mac Studio deployment (24/7, local Ollama)

The production target is a Mac Studio M4 Max (64 GB) running the stack 24/7 with a
**hot/cold** service split — see [`plans/lifecycle.md`](../plans/lifecycle.md) for the full
design. The `deploy-mvp` step just gets the stack booting; hot/cold + the supervisor come
in the `LC-*` slices.

LLM inference is **local Ollama on the host** (native Metal, not a container). Reach it from
inside the compose network via `host.docker.internal:11434`.

**One command (recommended)** — clone the repo, then from the repo root:

```sh
# macOS (Homebrew)
./scripts/bootstrap-mac.sh    # tools/apps (Brewfile) + ollama + models (~26 GB)
./scripts/start-mac.sh        # ollama + full compose stack (builds images on first run)
```
```powershell
# Windows (winget) — local dev/run; the 24/7 production target stays the Mac
.\scripts\bootstrap-win.ps1   # tools/apps (winget-packages.json) + models
.\scripts\start-win.ps1       # full compose stack
```

Between bootstrap and start: apply `.env.mac.example` into `infra/.env` and fill the 4 secrets.
Both bootstraps are idempotent; `SKIP_MODELS=1` (macOS) / `$env:SKIP_MODELS='1'` (Windows) installs tools
only. The toolset lives in [`Brewfile`](../Brewfile) / [`winget-packages.json`](../winget-packages.json),
the models in `scripts/pull-models.*`.

**Manual equivalent:**

```sh
cd infra
cp .env.example .env
# apply the Mac/Ollama overlay (LLM block + secrets) — see .env.mac.example
brew install ollama && ollama serve &          # host, native
# pull the models listed in .env.mac.example (~26 GB one-time)
docker compose -f docker-compose.yml up -d --build
docker compose -f docker-compose.yml logs -f gateway-telegram
```

The LLM defaults in `.env.example` are `mock` (for CI/tests) — the overlay in
[`.env.mac.example`](.env.mac.example) switches `LLM_PROVIDER` to `openai-compatible`,
points `LLM_BASE_URL` at host Ollama, and names the real chat/fast/vision/embedding models.
Fill the four required secrets there too (`GATEWAY_TELEGRAM_BOT_TOKEN`, the two internal
tokens, CalDAV creds).

Only `gateway-telegram` exposes a host port (default 8080) — everything else
talks via the internal `ai-life` docker network. `postgres` / `radicale` host
ports are still exposed for psql / Radicale UI access.

## Stop

```sh
docker compose -f docker-compose.dev.yml down       # or docker-compose.yml
```

Add `-v` to also remove the volumes (`postgres-data`, `radicale-data`, `minio-data`
in dev; `postgres-data`, `radicale-data` in full) if you want a clean slate.

## Database backups

The **full stack** runs a `postgres-backup` sidecar (OSS
[`prodrigestivill/postgres-backup-local`](https://github.com/prodrigestivill/docker-postgres-backup-local))
that takes a **daily** `pg_dump` of the whole `ailife` database, gzips it, and rotates copies locally —
no host cron/launchd, so it behaves identically on the Mac and on Windows. It's **not** in
`docker-compose.dev.yml`: dev data is disposable, so backups run only on the 24/7 deployment.

- **What's covered:** one dump of the whole DB → **every schema** (all ai-life domain schemas *and*
  coding-agent's `code.*`, which shares this Postgres). `POSTGRES_EXTRA_OPTS` deliberately carries **no
  `--schema` filter** so nothing is excluded.
- **Where:** `infra/backups/` (bind-mounted to `/backups`), gitignored. Subdirs `last/`, `daily/`,
  `weekly/` hold the gzipped dumps.
- **Schedule + retention:** `@daily`, keep **7 daily + 4 weekly** (`BACKUP_*` in `.env` /
  `.env.example`; defaults in `docker-compose.yml`).

**Trigger a backup now** (e.g. before a risky migration):

```sh
docker exec ai-life-postgres-backup /backup.sh
```

**Restore** a dump into the running Postgres (the dumps use `--clean --if-exists`, so they drop and
recreate objects — restoring over the live DB is destructive; take a fresh dump first):

```sh
gunzip -c infra/backups/last/ailife-latest.sql.gz \
  | docker exec -i ai-life-postgres psql -U ailife -d ailife
```

Backups are **local only** — a lost disk loses them. Off-site replication (a second host over
Tailscale, or a cloud bucket) is a deliberate follow-up, tracked in `plans/STATUS.md`.

## Services and default ports

| service              | port | purpose                                                       |
|----------------------|------|---------------------------------------------------------------|
| postgres             | 5432 | core DB (pgvector + pg_trgm; AGE later)                       |
| radicale             | 5232 | CalDAV server (calendar source of truth)                      |
| grafana              | 3000 | Zero-code finance dashboards over `finance.*` matviews (provisioned; see `grafana/`) |
| minio                | 9000 / 9001 | S3-compatible object store / web console               |
| gateway-telegram     | 8080 | Telegram webhook receiver — **the only externally-exposed app** |
| llm-gateway          | 8081 | Provider-agnostic LLM channel                                 |
| profile-service      | 8082 | Identity / households / people                                |
| orchestrator         | 8083 | Intent routing                                                |
| notifier-service     | 8084 | Outbound push (back through gateway-telegram)                 |
| scheduler-service    | 8085 | Cron / one-shot triggers                                      |
| calendar-agent       | 8086 | Calendar domain agent                                         |
| memory-service       | 8087 | pgvector recall + single-hop relations                        |
| media-service        | 8088 | media catalogue (MinIO blobs + metadata)                      |
| conversation-service | 8089 | short-term conversation control state (route-lock + pending)  |
| mcp-caldav           | 8090 | CalDAV CRUD MCP                                               |
| mcp-ics-import       | 8091 | ICS subscription puller                                       |
| mcp-finance          | 8092 | Finance CRUD MCP                                              |
| finance-agent        | 8093 | Finance domain agent                                          |
| mcp-money-pro-import | 8094 | Money Pro CSV importer                                        |
| mcp-tasks            | 8095 | Tasks/GTD CRUD MCP                                            |
| tasks-agent          | 8096 | Tasks/GTD domain agent                                        |
| mcp-media-processing | 8097 | Media-understanding capability-MCP (OCR + vision caption + STT)  |
| mcp-web              | 8098 | Web capability-MCP (web_search + fetch_url) over SearXNG         |
| researcher-agent     | 8099 | Web research specialist (binds mcp-web)                          |
| mcp-market-data      | 8100 | Market-data capability-MCP (read-only `quote` over Stooq)        |
| mcp-wardrobe         | 8101 | Wardrobe CRUD MCP (garments + style profile)                     |
| stylist-agent        | 8102 | Stylist domain agent (binds mcp-wardrobe + media-processing + web) |
| mcp-image-gen        | 8103 | Image-generation capability-MCP (stub now, local model later)    |
| mcp-nutrition        | 8104 | Nutrition CRUD MCP (meal log + diet profile + basket)            |
| nutritionist-agent   | 8105 | Nutrition domain agent (binds mcp-nutrition + media-processing + web) |
| chef-agent           | 8106 | Recipe specialist (binds mcp-nutrition + web; invoked by nutritionist) |
| mcp-food-data        | 8107 | Food-data capability-MCP (read-only `food_lookup` over Open Food Facts) |
| mcp-creator          | 8108 | Creator CRUD MCP (content track + trend cache + idea/draft history) |
| creator-agent        | 8109 | Creator/content-factory agent (binds mcp-creator + web)         |
| mcp-youtube          | 8110 | Video-trends capability-MCP (`youtube_trends` over YouTube Data API v3) |
| mcp-reddit           | 8111 | Social-trends capability-MCP (`reddit_trends` over the Reddit API) |
| mcp-feeds            | 8112 | Feeds capability-MCP (`feed_items` over RSS/Atom + public Telegram) |
| mcp-weather          | 8113 | Weather capability-MCP (read-only `forecast` over Open-Meteo)     |
| mcp-briefing         | 8114 | Briefing domain-MCP (per-person digest prefs: location/interests/sections/schedule) |
| briefing-agent       | 8115 | Morning-digest agent (binds mcp-briefing + mcp-weather + mcp-web)  |
| mcp-docs             | 8116 | Docs domain-MCP (document archive: store + metadata + text search) |
| docs-agent           | 8117 | Document-archive agent (ingest a doc photo → OCR → archive; binds mcp-docs + mcp-media-processing) |
| notes-agent          | 8118 | Second-brain / notes agent ("запомни …" / "что я думал про …"; binds memory-service, no own MCP) |
| coordinator-agent    | 8119 | Cross-cutting synthesis engine (#290): multi-domain request → read second brain → one answer; reactive + proactive, no own MCP |
| mcp-chart-render     | 8120 | Chart-render capability-MCP (data → PNG via Java2D → media-service; `render_chart`) |
| mcp-coach            | 8121 | Coach domain-MCP (subject-scoped coaching record: profile/values/observations/hypotheses/actions/sessions/intake) |
| coach-agent          | 8122 | Self-understanding coach (#289): safety gate → Reflect over the sender's own notes; binds memory-service + mcp-coach `/internal` |
| searxng              | 8888 | Self-hosted meta-search (backing service for mcp-web; JSON API)  |
| whisper              | 9100 | Self-hosted ASR sidecar (real STT for mcp-media-processing's `transcribe`) |

Default credentials are in `.env.example`. Replace them in your `.env` before
exposing anything to a network.

## DB conventions

Schemas split by **bounded context**, not by service:

| schema   | owner / writers                                            |
|----------|------------------------------------------------------------|
| core     | profile-service, orchestrator, gateway-telegram            |
| memory   | memory-service                                             |
| audit    | every service (writes); observability (reads)              |
| bus      | event-bus library (outbox + LISTEN/NOTIFY)                 |
| media    | media-service (writes); gateway-telegram + agents (via media-service) |
| calendar | mcp-caldav, calendar-agent, scheduler-service              |
| finance  | mcp-finance, finance-agent, mcp-money-pro-import           |
| tasks    | mcp-tasks, tasks-agent                                     |
| wardrobe | mcp-wardrobe, stylist-agent                                |
| nutrition | mcp-nutrition, nutritionist-agent, chef-agent             |

All Liquibase changesets are applied by one job; ordering is controlled by
`db.changelog-master.xml`.
