# Platform / shared services

## orchestrator (platform/, the "brain")
Accepts NormalizedMessage. Decides: single agent / chain / direct reply. Holds short conversation context per user + calls memory-service recall (long). Applies policy (future: child accounts). Streams reply via SSE → gateway → Telegram (edit message in chunks).
IntentRouter → `LlmIntentClassifier` on `fast` channel; few-shot prompt built from registered agents' manifests (`GET /agents/{name}/manifest` at startup); fallback → echo.
Also the single entry the scheduler uses to wake any agent (so human-triggered and schedule-triggered wake-ups are identical).

## gateway-telegram (platform/)
Long polling at start → webhook when TLS. Resolve telegram_user_id → user/household/scope. Store incoming media in MinIO, pass links. BEFORE orchestrator call mcp-media-processing: audio→STT, image→vision-caption+OCR, video→keyframes+STT, file→text. Output: unified NormalizedMessage. Bot token lives ONLY here; `/internal/send` endpoint (shared INTERNAL_API_TOKEN) used by notifier.

### Multimodal & reply UX ([#489](https://github.com/fedoroff-vlad/ai-life/issues/489), road-test)
The daily surface is Telegram (voice / photo / text); its rough edges are felt on every interaction. A broad
epic sliced small:
- **RU-1 quick ack (typing indicator) — ✅ DONE.** `bot/TypingIndicator` shows "печатает…" for the whole slow
  round-trip (upload → STT → orchestrator → agent → reply) so the owner sees the bot heard them instead of
  silence. Telegram's `sendChatAction` lapses after ~5s, so it is **kept alive** — fired once immediately, then
  refreshed every ~4s on a daemon scheduler (the long-poll thread is blocked in `process().block()`, so the
  refresh runs off it) until the reply is sent (a try-with-resources `Handle` in `AiLifeBot.consume`). Purely
  cosmetic + best-effort: a chat-action failure is swallowed, never delaying or breaking the reply.
- **RU-2 inline buttons for confirmations — ✅ DONE.** A binary-confirm reply now carries a Telegram
  inline **Да / Нет** keyboard; a tap (callback query) is decoded to the same "да"/"нет" text the user would
  type and routed through the normal orchestrator path, where the active conversation route-lock resumes the
  awaiting agent — so the whole resume path is unchanged (no contract/agent change). Which replies get buttons
  is opt-in: the shared confirm-act runner (`PickConfirmActRunner`) marks its confirm `pendingAction` with the
  `PendingActionHints.CONFIRM` hint, and only a hinted reply gets a keyboard — an open-question `pendingAction`
  (a clarify, a "личное/общее?" sharing confirm) still expects free text. The tap answers the callback (stops
  the client spinner) and strips the keyboard so it's one-shot (both best-effort). The gateway's
  `ConfirmKeyboard` is the shared button/callback primitive **PX-4** (proactive snooze/dismiss) will extend.
- **RU-3 STT reliability — ✅ DONE.** A voice note is the *only* payload the owner sent, so routing a garbled
  or empty transcript wastes a turn (the orchestrator classifies noise, an agent acts on the wrong text). The
  fix is a **front-door quality gate**: `mcp-media-processing`'s whisper engine now derives a `0..1` recognition
  `confidence` on `TranscriptResult` (`exp(mean segment avg_logprob)`; `0.0` on empty text, `null` when the
  engine reports no signal), and gateway-telegram, after transcribing a captionless voice note, checks it — an
  **empty transcript** or one **below `gateway.stt.min-confidence`** (default `0.55`) is treated as
  unintelligible: the gateway replies "не расслышал, повтори голосом ещё раз" and **does not route**, so the
  owner just re-records instead of getting a wrong action. A `null` confidence is "unknown", never low, so a
  transcript still routes when the engine gives no signal (back-compat). Deterministic, never an LLM call.
- **RU-4 photo/receipt robustness** (unreadable → ask for a clearer shot) — deferred; `mcp-media-processing`
  owns most (the OCR/caption twin of this gate).

**Acceptance criteria (WHEN/THEN) — RU-3:**
- Scenario: **a confident voice note routes.** WHEN a captionless voice note transcribes to non-empty text with
  confidence ≥ `gateway.stt.min-confidence` (or a `null`/unknown confidence) → THEN the gateway routes the
  transcript to the orchestrator exactly as before.
- Scenario: **a low-confidence voice note asks for a repeat.** WHEN the transcript's confidence is known and
  below the threshold → THEN the gateway replies asking the owner to repeat and never calls the orchestrator.
- Scenario: **an empty transcript asks for a repeat.** WHEN the transcript text is empty/blank (no speech heard)
  → THEN the gateway replies asking the owner to repeat and never calls the orchestrator.
- Scenario: **a captioned voice / typed message is unaffected.** WHEN the message already carries text (a
  caption or a typed message) → THEN no STT gate applies and it routes normally.

**Acceptance criteria (WHEN/THEN) — RU-1:**
- Scenario: **slow flow shows a typing indicator.** WHEN an inbound message starts a round-trip that takes more
  than a moment → THEN the owner sees "печатает…" immediately and continuously until the reply arrives, not
  silence (`TypingIndicator.start` fires now + refreshes every ~4s; the `Handle` stops it when the reply sends).
- Scenario: **typing never breaks the reply.** WHEN the typing chat-action send fails → THEN it is swallowed and
  the actual reply is still produced and sent (best-effort, cosmetic-only).

**Acceptance criteria (WHEN/THEN) — RU-2:**
- Scenario: **binary confirm renders buttons.** WHEN an agent reply carries a `pendingAction` hinted
  `PendingActionHints.CONFIRM` → THEN the bot sends the reply text with a two-button Да / Нет inline keyboard.
- Scenario: **open-question pendingAction gets no buttons.** WHEN a `pendingAction` lacks the confirm hint (a
  clarify, a "личное/общее?" sharing confirm) → THEN no keyboard is attached (it expects a free-text answer).
- Scenario: **a tap confirms via resume.** WHEN the owner taps "Да" → THEN the gateway routes the affirmative
  text "да" through the orchestrator, the route-lock resumes the awaiting agent, and its reply is shown.
- Scenario: **a tap cancels via resume.** WHEN the owner taps "Нет" → THEN the gateway routes "нет" (non-
  affirmative) → the agent leaves the action, and the decline reply is shown.
- Scenario: **buttons are one-shot + acknowledged.** WHEN a button is tapped → THEN the callback query is
  answered (spinner stops) and the prompt's keyboard is stripped so it can't be tapped again (both best-effort).

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
**PX-2** frequency cap — a per-user `daily_cap` on proactive deliveries; a delivered proactive push is logged
in `core.notification_sent`, and once today's count hits the cap the overflow is **suppressed** (dropped, not
queued — owner-decided: a cap that defers just moves the spam, and an overnight-defer collides with quiet hours
+ the stale TTL). **PX-3** per-stream opt-out (`stream` on the contract + preference; a member mutes one stream
while others keep it). **PX-3a** the mechanism — `source`/stream on the notify contract + a
`core.notification_stream_optout` set + a gate check (a muted stream is suppressed, checked before quiet
hours/cap); **PX-3b** the producer stream-name rollout (each proactive send passes its coarse stream via
`NotifierClient.notify(userId, text, stream)` — the canonical ids: `briefing` · `resurfacing` · `calendar` ·
`finance` · `tasks` · `nutrition` · `coordinator` · `ambient`). The chat UX that *writes* the opt-out row is
folded into **PX-4** (owner-decided: opt out via the snooze/dismiss button on the push itself). **PX-4**
snooze/dismiss inline buttons (gateway-telegram keyboard + callback → record the preference; coordinates
with the #489 button infra).

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

**Acceptance criteria (WHEN/THEN) — PX-2:**
- Scenario: **overflow is suppressed.** WHEN a user has already had `daily_cap` proactive pushes delivered
  today (their tz) and another proactive send arrives → THEN it is dropped (not delivered, not queued), and a
  `202` is returned to the caller.
- Scenario: **under the cap delivers and counts.** WHEN today's delivered count is below the cap → THEN the
  push is delivered and recorded in `core.notification_sent`, so it counts toward the cap.
- Scenario: **no cap = unlimited.** WHEN `daily_cap` is null → THEN no capping (only quiet hours may gate).
- Scenario: **yesterday does not count.** WHEN deliveries happened on prior days → THEN they do not count
  against today's cap (the count is per calendar day in the user's tz).

**Acceptance criteria (WHEN/THEN) — PX-3:**
- Scenario: **a muted stream is suppressed.** WHEN a proactive send's `source` (stream) is in the user's
  `notification_stream_optout` set → THEN it is dropped, and checked **before** quiet hours/cap (a muted
  stream is never held or counted); other streams still deliver.
- Scenario: **muting is per member.** WHEN one household member mutes a stream → THEN only that member stops
  receiving it (the opt-out row is per user).
- Scenario: **unattributed send is never opt-out-able.** WHEN a proactive send has no `source` → THEN no
  stream opt-out can match it (it still faces quiet hours/cap).

## Schemas owned here
- `memory` — pgvector + AGE.
- `audit` — events + LLM trace fallback (if not Langfuse).
- `bus` — outbox + LISTEN/NOTIFY (libs/event-bus).
- `media` — file metadata (files themselves in MinIO).
- `core.shedlock`, `core.schedules`, `core.conversations`, `core.sessions`.

## Observability / secrets
Langfuse (LLM traces), Prometheus + Grafana + Loki. Secrets: Vault dev-mode or encrypted PG table at start.
