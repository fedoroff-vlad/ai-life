# Platform / shared services

## orchestrator (platform/, the "brain")
Accepts NormalizedMessage. Decides: single agent / chain / direct reply. Holds short conversation context per user + calls memory-service recall (long). Applies policy (future: child accounts). Streams reply via SSE → gateway → Telegram (edit message in chunks).
IntentRouter → `LlmIntentClassifier` on `fast` channel; few-shot prompt built from registered agents' manifests (`GET /agents/{name}/manifest` at startup); fallback → echo.
Also the single entry the scheduler uses to wake any agent (so human-triggered and schedule-triggered wake-ups are identical).

## gateway-telegram (platform/)
Long polling at start → webhook when TLS. Resolve telegram_user_id → user/household/scope. Store incoming media in MinIO, pass links. BEFORE orchestrator call mcp-media-processing: audio→STT, image→vision-caption+OCR, video→keyframes+STT, file→text. Output: unified NormalizedMessage. Bot token lives ONLY here; `/internal/send` endpoint (shared INTERNAL_API_TOKEN) used by notifier.

## llm-gateway (platform/)
Single LLM entry. Channels default/fast/vision/embedding. Provider via env (mock/anthropic/openai-compatible/Ollama). Tracing via Langfuse. See architecture.md §LLM strategy.

## memory-service (platform/)
pgvector (embeddings) + Apache AGE (graph: Person/Place/Item/Event, edges likes/owns/related_to). Three levels: short-term (core.conversations), user long-term (scope user:<id>), household shared (scope household:<id>). Each record: scope, source, embedding (via the `embedding` channel — `nomic-embed-text`/768 on the Mac deploy, mock/384 in dev), graph_refs.
API: `POST /remember`, `POST /recall` (top-k + scope filter + optional graph-walk), `POST /forget`, `GET /graph/person/{id}/relations`. Orchestrator calls recall before routing.

## media-service (platform/, port 8088)
Central media catalogue. Bytes live in **MinIO** (S3-compatible object store, raised in Docker like Postgres); metadata in `media.media_object`. REST: `POST /v1/media` (multipart upload → `MediaObjectDto`), `GET /v1/media/{id}` (raw bytes), `GET /v1/media/{id}/meta`, `DELETE /v1/media/{id}`. Callers reference an object only by `id` — bucket/key layout is internal. Every media-ingesting path goes through here instead of carrying raw bytes: finance receipts first, then future nutrition / stylist / researcher agents. No auth (internal-only); fetch is household-agnostic (caller authorized upstream). Image vision-caption / audio STT (`mcp-media-processing`) is a separate later layer — this service is storage + metadata only.

## scheduler-service (platform/, port 8085)
`@EnableScheduling` + ShedLock (JDBC, `core.shedlock`). Table `core.schedules (id, owner_agent, cron/rrule, kind, payload jsonb, enabled, next_run_ts)`. Tick selects due jobs → POST orchestrator to wake target agent with payload. Recomputes next_run (recurring) or marks done (one_off). Does NOT think/format — only triggers. Tools (mcp-scheduler): schedule_once, schedule_recurring, list_jobs, pause/resume/cancel_job.

## notifier-service (platform/, port 8084)
`POST /v1/notify {userId, text}` → lookup telegram_user_id via profile-service → POST gateway-telegram `/internal/send`. Bot token stays in gateway only.

### Proactive UX — quiet hours / caps / snooze ([#487](https://github.com/fedoroff-vlad/ai-life/issues/487), road-test)
Every proactive push (briefing digest, birthday chain, resurfacing, ambient acks, scheduler wakes) flows
through notifier — both `POST /v1/notify` and the `notify.requested` bus event — while a reactive reply the
user asked for goes straight through gateway-telegram, **not** notifier. So notifier is the single seam for a
send-time gating layer that makes proactive UX controllable. A `proactive` flag on the notify contract marks
which sends are gated (default `false` → reactive, never gated; back-compat via a secondary ctor so existing
callers don't ripple). Per-user preferences live in a new `core.notification_preference` (absent row → no
gating, back-compat). Deterministic mechanism, never an LLM decision.

**Slices** (each a small vertical): **PX-1** quiet-hours hold+redeliver — **PX-1a** store + `proactive` flag +
gate that *holds* a proactive send due in the user's quiet window into `core.notification_held`; **PX-1b** the
redrain tick that redelivers a held message when the window opens (drops it if older than a staleness TTL).
**PX-2** frequency cap (coalesce/defer under a daily ceiling). **PX-3** per-stream opt-out (`stream` on the
contract + preference; a member mutes one stream while others keep it). **PX-4** snooze/dismiss inline buttons
(gateway-telegram keyboard + callback → record the preference; coordinates with the #489 button infra).

**Acceptance criteria (WHEN/THEN) — PX-1:**
- Scenario: **proactive push in quiet hours is held.** WHEN a `proactive` send is due for a user inside their
  quiet-hours window (evaluated in the user's tz) → THEN notifier does **not** deliver it; it stores the message
  in `core.notification_held` with `deliver_after` = the window's next end, and returns a "held" outcome (PX-1a).
- Scenario: **reactive send is never gated.** WHEN a send is reactive (`proactive=false`/absent) → THEN it
  delivers immediately regardless of quiet hours.
- Scenario: **no preference = no gating.** WHEN the user has no `notification_preference` row → THEN every send
  delivers immediately (back-compat).
- Scenario: **window wraps midnight.** WHEN quiet hours span midnight (e.g. 22:00–08:00) → THEN a 03:00-local
  send is correctly detected as inside the window (pure `QuietHours` logic, unit-tested).
- Scenario: **redeliver at window open.** WHEN the quiet window ends and a held message is still fresh (held
  under the staleness TTL) → THEN notifier redelivers it; a held message older than the TTL is dropped, never
  delivered late (PX-1b).

## Schemas owned here
- `memory` — pgvector + AGE.
- `audit` — events + LLM trace fallback (if not Langfuse).
- `bus` — outbox + LISTEN/NOTIFY (libs/event-bus).
- `media` — file metadata (files themselves in MinIO).
- `core.shedlock`, `core.schedules`, `core.conversations`, `core.sessions`.

## Observability / secrets
Langfuse (LLM traces), Prometheus + Grafana + Loki. Secrets: Vault dev-mode or encrypted PG table at start.
