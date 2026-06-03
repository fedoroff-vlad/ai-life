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
pgvector (embeddings) + Apache AGE (graph: Person/Place/Item/Event, edges likes/owns/related_to). Three levels: short-term (core.conversations), user long-term (scope user:<id>), household shared (scope household:<id>). Each record: scope, source, embedding (bge-m3 via embedding channel), graph_refs.
API: `POST /remember`, `POST /recall` (top-k + scope filter + optional graph-walk), `POST /forget`, `GET /graph/person/{id}/relations`. Orchestrator calls recall before routing.

## scheduler-service (platform/, port 8085)
`@EnableScheduling` + ShedLock (JDBC, `core.shedlock`). Table `core.schedules (id, owner_agent, cron/rrule, kind, payload jsonb, enabled, next_run_ts)`. Tick selects due jobs → POST orchestrator to wake target agent with payload. Recomputes next_run (recurring) or marks done (one_off). Does NOT think/format — only triggers. Tools (mcp-scheduler): schedule_once, schedule_recurring, list_jobs, pause/resume/cancel_job.

## notifier-service (platform/, port 8084)
`POST /v1/notify {userId, text}` → lookup telegram_user_id via profile-service → POST gateway-telegram `/internal/send`. Bot token stays in gateway only.

## Schemas owned here
- `memory` — pgvector + AGE.
- `audit` — events + LLM trace fallback (if not Langfuse).
- `bus` — outbox + LISTEN/NOTIFY (libs/event-bus).
- `media` — file metadata (files themselves in MinIO).
- `core.shedlock`, `core.schedules`, `core.conversations`, `core.sessions`.

## Observability / secrets
Langfuse (LLM traces), Prometheus + Grafana + Loki. Secrets: Vault dev-mode or encrypted PG table at start.
