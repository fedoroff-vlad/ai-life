# gateway-telegram

Telegram entry point. Long-polling bot that:
1. Receives Telegram updates.
2. Resolves identity via `profile-service` — on first contact creates the user's **personal household**
   (named after them, ADR-0001) + user as `admin`, never attaching them to another user's household.
3. Builds a `NormalizedMessage` and sends it to `orchestrator`.
4. Replies to the chat with the orchestrator's response.

**Quick ack (typing indicator, road-test [#489](https://github.com/fedoroff-vlad/ai-life/issues/489) RU-1).**
A round-trip (upload → STT → orchestrator → agent) can take several seconds, so the bot shows Telegram's
"печатает…" for the whole flow — fired immediately and **kept alive** (Telegram's action lapses after ~5s, so
it's refreshed every ~4s on a daemon scheduler while the long-poll thread blocks on the reply) until the reply
is sent. Purely cosmetic + best-effort: a chat-action failure is swallowed, never delaying or breaking the reply.

**Inline confirm buttons (road-test [#489](https://github.com/fedoroff-vlad/ai-life/issues/489) RU-2).** A
binary-confirm reply — an agent `pendingAction` hinted `PendingActionHints.CONFIRM` (set by the shared
`PickConfirmActRunner`) — is sent with a **Да / Нет** inline keyboard. A tap arrives as a callback query; the
gateway decodes it to the same "да"/"нет" text the user would type and routes it through the normal
orchestrator path, where the active conversation route-lock resumes the awaiting agent — so no resume-specific
plumbing lives here. The tap answers the callback (stops the client spinner) and strips the keyboard so it's
one-shot (both best-effort). An open-question `pendingAction` without the hint (a clarify, a "личное/общее?"
sharing confirm) gets **no** keyboard — it still expects a free-text answer. `ConfirmKeyboard` is the shared
button/callback primitive the proactive snooze/dismiss buttons (#487 PX-4) will extend.

**Family invites (ADR-0001 slice 4b).** Two owner-gated commands, both handled at the gateway level
(identity plumbing, not routable messages):
- **`/invite <name> as <relationship>` (slice 4b-ii)** — the owner mints a pre-authorized invite into
  their own household via `profile-service` (`POST /v1/invites`); the bot replies with a
  `t.me/<bot>?start=<token>` deep-link to forward out-of-band. `<name>` is a label for the reply only;
  `<relationship>` tags the invitee. A bare `/invite` replies with usage.
- **`/start <token>` (slice 4b-i)** — a deep-link opens as the text `/start <token>`; the gateway
  resolves/creates the opener's identity, then redeems the invite
  (`POST /v1/invites/by-token/{token}/redeem`) so they join the inviter's family household, replies to
  the invitee with a join confirmation, and DMs the **holder** (inviter) that they joined. An unknown /
  already-used token is graceful — the opener just keeps their own isolated personal space.

**Front-door QR read (photographed label, inventory [IN-f2](../../plans/inventory.md)).** A scan also
arrives as a *picture* of the sticker, and a captionless photo has no text for the orchestrator to
classify — so, exactly like the voice STT above, the conversion happens at the front door: the uploaded
photo goes to `mcp-media-processing`'s `POST /internal/qr` (ZXing, deterministic, no model) and a decoded
`…?start=box_<token>` payload takes the same dispatch as the deep-link below. Two deliberate limits:
only a **captionless** photo is read (a caption means the owner is *saying* something about the picture,
and a scan must not hijack "добавь сюда ещё одну вещь"), and the decode is **soft-failed** — unlike a
voice note, where the transcript *is* the payload, a photo already has a perfectly good route, so a
missing code, a slow capability or a dead one all cost nothing (3 s timeout, then route as before).
Toggle with `GATEWAY_QR_SCAN_ENABLED`.

**Container-label scan (`/start box_<token>`, inventory [IN-f1](../../plans/inventory.md)).** Two
unrelated deep-links share the one `/start` path, told apart by **prefix**: a payload starting with
`BoxDeepLink.PREFIX` (`box_`) is a scanned storage-container label, anything else stays a family invite,
unchanged. A scan carries **no sentence to classify** — the token names the box — so it is *not* routed
as a message: the gateway dispatches it through the hub's existing inter-agent `invoke`
(`POST /v1/agents/invoke` → inventory's `show_container`) and shows the agent's own reply, ok or not.
That keeps a guess out of the one question a sticker must answer exactly, and adds no wire contract.
Identity resolves as for any message, so the owner-allowlist below still governs first contact — a
sticker authorizes seeing *that container*, never creating an account. A scan is a read (re-opening the
link repeats it), so it is not written to the durable inbox. The `box_` literal + its parser live in
`libs/contracts`' `BoxDeepLink`, shared with the QR renderer: a prefix that drifted on one side would
orphan every label already glued to a box.

Photo, document and voice messages are supported: the bytes are downloaded and uploaded to
`media-service`, and the returned object id rides on the `NormalizedMessage` as an attachment
(`storageUri` = the media object id; the caption becomes `text`). Photos get `kind=image` (receipt
flow); documents get `kind=file` (e.g. a Money Pro CSV), except an image sent uncompressed as a
document which keeps `kind=image`. A downstream agent fetches the bytes back from media-service by
that id.

**Voice notes (`kind=voice`) — front-door STT.** A voice note carries no caption, so after the audio
is uploaded the gateway transcribes it to text via `mcp-media-processing`'s `POST /internal/transcribe`
passthrough (whisper) and puts the transcript into `text`. The orchestrator then classifies + routes it
exactly like a typed message — so a spoken request reaches any agent, not a single one. Transcription is
**not soft-failed**: for a voice message the transcript is the payload, so an STT failure surfaces as an
error reply rather than a silent empty route. Video lands later alongside the same capability.

**STT reliability gate (road-test [#489](https://github.com/fedoroff-vlad/ai-life/issues/489) RU-3).** A voice
note is the owner's only payload, so routing a garbled transcript wastes a turn. The transcribe passthrough now
returns a `0..1` `confidence` on `TranscriptResult`; the gateway checks it before routing. An **empty** transcript
(no speech) or one whose confidence is **known and below `gateway.stt.min-confidence`** (default `0.55`, an
internal tunable — env `GATEWAY_STT_MINCONFIDENCE`) is treated as unintelligible: the bot replies "не расслышал,
повтори голосом ещё раз" and **does not call the orchestrator**, so the owner just re-records. A `null` confidence
(engine reported no signal) is "unknown", never low, so the transcript still routes (back-compat). Deterministic,
never an LLM call.

**Owner-allowlist onboarding ([#627](https://github.com/fedoroff-vlad/ai-life/issues/627)).** The bot
auto-provisions a personal household for a brand-new sender (see step 2 above), so an ungated bot lets
anyone who finds it consume the owner's LLM budget — data stays isolated per household, but the *spend*
does not. `GATEWAY_ALLOWED_TELEGRAM_IDS` (CSV of Telegram user ids) gates **first-contact account
creation**: an unlisted new id is declined with an invite-only reply and **never reaches the
orchestrator/LLM**. Two paths bypass the gate on purpose — an **already-provisioned** user (onboarded
earlier) always passes, and a **`/start <token>` family invite** onboards the invitee regardless (the
token is the authorization). **Empty (the default) = allow all**, preserving the pre-#627 behaviour for
dev/CI/local runs; set it in prod to your own id plus anyone you trust. `IdentityResolver.resolve` is the
gate; a stranger's `/invite` is gated too (else minting would be a trivial bypass).

**Durable inbound inbox ([#633](https://github.com/fedoroff-vlad/ai-life/issues/633)).** The inbound path
`gateway → orchestrator → agent → MCP → LLM` is synchronous, so before #633 any downstream failure meant the
user's message was logged and **dropped**. The gateway now **persists-before-process**: right before it calls
the orchestrator, `MessageProcessor.dispatch` writes the normalized message + reply target to `bus.inbox` (via
[`libs/inbox`](../../libs/inbox/README.md) — the outbox's inbound mirror), deduped on the Telegram `update_id`.
On success the row is marked `PROCESSED`; on a downstream outage it stays `PENDING`, the user gets a *"сервис
временно недоступен — поставил в очередь"* notice instead of a silent drop, and a background redriver
(`GatewayInboxHandler` on `libs/inbox`'s `InboxRedriverContainer`) re-attempts due rows with backoff and
delivers the answer once the outage clears — retiring a poison message to `DEAD` + a dead-letter notice after
`INBOX_MAX_ATTEMPTS`. Media is already durable in media-service, so a redrive re-dispatches without
re-uploading. This gives the gateway a **direct Postgres connection** (its only stateful dependency); the
`bus.inbox` schema is applied by the central Liquibase container, and the DataSource is configured to boot and
degrade gracefully when the DB is briefly down (`minimum-idle: 0`, `initialization-fail-timeout: -1`), so
durability is best-effort — a DB blip falls back to plain dispatch, never worse than pre-#633. The redrive loop
starts **only when the bot token is set** (there must be a bot to deliver the reply). Distinct from #631
(in-request breaker/retry) and the outbox (async *outbound*).

## Configuration

| env var                          | default                              | required |
|----------------------------------|--------------------------------------|----------|
| `GATEWAY_PORT`                   | `8080`                               |          |
| `GATEWAY_TELEGRAM_BOT_USERNAME`  | `ai_life_bot`                        |          |
| `GATEWAY_TELEGRAM_BOT_TOKEN`     | *(empty — bot won't start)*          | yes for prod |
| `GATEWAY_DEFAULT_HOUSEHOLD_NAME` | `default household`                  |          |
| `GATEWAY_ALLOWED_TELEGRAM_IDS`   | *(empty — allow all)*                | prod (see below) |
| `GATEWAY_QR_SCAN_ENABLED`        | `true`                               | (front-door QR read of a captionless photo, IN-f2) |
| `GATEWAY_DB_URL`                 | `jdbc:postgresql://localhost:5432/ailife` | (durable inbox #633) |
| `GATEWAY_DB_USER`                | `ailife`                             |          |
| `GATEWAY_DB_PASSWORD`            | `ailife`                             |          |
| `INBOX_ENABLED`                  | `true`                               | (gates the redrive loop; writer always on) |
| `INBOX_POLL_INTERVAL`            | `15s`                                |          |
| `INBOX_INITIAL_DELAY`            | `30s`                                | (grace before a fresh row is redrive-eligible) |
| `INBOX_BACKOFF` / `INBOX_MAX_BACKOFF` | `30s` / `10m`                   |          |
| `INBOX_MAX_ATTEMPTS`             | `6`                                  | (attempts before a message goes `DEAD`) |
| `PROFILE_SERVICE_URL`            | `http://profile-service:8082`        |          |
| `ORCHESTRATOR_URL`               | `http://orchestrator:8083`           |          |
| `MEDIA_SERVICE_URL`              | `http://media-service:8088`          |          |
| `MCP_MEDIA_PROCESSING_URL`       | `http://mcp-media-processing:8097`   | (voice STT passthrough) |

If the token is empty the HTTP server still starts (so `/actuator/health` works) but the
bot doesn't connect — handy for CI and local IDE runs.

## How to get a token (Stage 0)

Talk to `@BotFather` on Telegram, run `/newbot`, follow the prompts. Paste the token
into `infra/.env`.

## Run locally

Bring up the dev infra and the dependent services first:

```sh
docker compose -f infra/docker-compose.dev.yml up -d
mvn -B -pl platform/llm-gateway -am spring-boot:run        # in one terminal
mvn -B -pl platform/profile-service -am spring-boot:run    # in another
mvn -B -pl platform/orchestrator -am spring-boot:run       # in another
GATEWAY_TELEGRAM_BOT_TOKEN=... \
    mvn -B -pl platform/gateway-telegram -am spring-boot:run
```

Then DM your bot. The first message creates your personal household and you as `admin`.

## `POST /internal/send`

Internal-only outbound channel for notifier-service (notifier never touches the
Telegram bot API directly — the token stays here). Guarded by the central shared-secret
`/internal/*` filter in platform-common (`INTERNAL_SHARED_SECRET`, #630/ADR-0007) — the caller's
`Authorization: Bearer` header is added and checked centrally, not by this controller.
Body: [InternalSendRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/notify/InternalSendRequest.java).

## Key classes
- `GatewayApplication`.
- `bot/AiLifeBot` — Telegram bot impl. Intercepts a **callback query** (a confirm-button tap, #489 RU-2) before message dispatch — decodes it to "да"/"нет", answers the callback + strips the keyboard, routes it like a typed reply; then intercepts the deep-link commands before the normal media/text dispatch: `/start <payload>` splits by prefix into a **container scan** (`box_<token>` → `handleScan`, IN-f1) and a family-invite redemption (→ invitee reply + holder ping), and `/invite <name> as <relationship>` mints (owner → deep-link reply, or usage on a bare `/invite`). Attaches the confirm keyboard to a binary-confirm reply (`isBinaryConfirm` → `pendingAction` hinted `PendingActionHints.CONFIRM`).
- `bot/ConfirmKeyboard` — the RU-2 shared inline-button primitive (#489; PX-4 will extend it): builds the two-button Да / Нет keyboard (localised labels, stable `cf:y`/`cf:n` callback ids) and decodes a tap's `callback_data` back into the "да"/"нет" text a route-locked `/resume` expects.
- `bot/TypingIndicator` — the RU-1 quick-ack (#489): `start(chatId)` fires a `sendChatAction=typing` now and refreshes it every ~4s on a daemon scheduler, returning a `Handle` (`AutoCloseable`) the bot closes when the reply is sent. Best-effort — every send is swallowed on failure so the typing hint never delays or breaks the reply. `AiLifeBot.consume` wraps the whole dispatch in `try (var t = typing.start(chatId))`.
- `bot/BotRegistration` — long-poll registration; no-ops when token is empty.
- `bot/MessageProcessor` — also carries `showContainer(incoming, qrToken)`, the scanned-label dispatch (IN-f1/f2): resolve identity → hub `invoke` (`show_container`) → the agent's own text, with `SCAN_UNAVAILABLE` when inventory is cold/unregistered; `scannedLabel` is the front-door QR check on a captionless photo that feeds it (IN-f2, soft-fail). Otherwise normalises Telegram updates into `NormalizedMessage`; uploads any photo/document/voice to media-service first and attaches the returned object id. For a captionless voice note it transcribes the uploaded audio and either routes the transcript as `text` or — when it's empty/low-confidence — returns the RU-3 ask-to-repeat reply without routing (`route` / `unintelligible`, threshold `gateway.stt.min-confidence`). `dispatch` is the durable-inbox seam (#633): persist-before-process to `bus.inbox`, mark `PROCESSED` on success, reply "queued" (not drop) + leave `PENDING` for the redriver on a downstream outage. `IncomingMessage` now carries `chatId` + `updateId` for that (a null `updateId` disables durability — invite/callback/test paths).
- `inbox/GatewayInboxHandler` — the redrive + dead-letter handler on `libs/inbox`'s `InboxRedriverContainer`: re-dispatches a persisted message to the orchestrator and delivers the answer to the original chat; on terminal `DEAD`, DMs the user a "couldn't process" notice. `inbox/InboundEnvelope` is the serialised `bus.inbox` payload (chatId + languageCode + `NormalizedMessage`); `inbox/InboundReplies` holds the localised queued / dead-letter texts.
- `config/InboxWiringConfig` — `@Import`s `libs/inbox`'s `InboxConfig` (always-on `InboxWriter`) and registers the redrive container **only when the bot token is set** (nothing to deliver otherwise). Datasource + `inbox.*` tuning live in `application.yml`.
- `media/MediaServiceClient` — multipart `POST /v1/media` upload of media bytes → `MediaObjectDto`. Not soft-failed: for a media message the upload is the payload.
- `media/QrDecodeClient` — `POST /internal/qr {mediaId}` against `mcp-media-processing` → the decoded payload (IN-f2). The barcode twin of `TranscribeClient`, but **soft-failed** (empty on no-code / error / 3 s timeout): a photo already has a route, so the read must cost it nothing. `BoxDeepLink.tokenOfUrl` turns a matching payload into the container token.
- `media/TranscribeClient` — `POST /internal/transcribe {mediaId}` against `mcp-media-processing` → the full `TranscriptResult` (text + `confidence` for the RU-3 gate). Front-door STT for voice notes; not soft-failed: the transcript is the voice message's payload.
- `identity/IdentityResolver` — `tg_user_id → User` (creates the user + their personal household on first contact, ADR-0001). Also `redeemInvite(...)` — a `/start <token>` join (resolve → redeem → resolve inviter → `InviteOutcome`) — and `mintInvite(...)` — the owner-side mint (resolve → `POST /v1/invites` → format the `t.me/<bot>?start=<token>` deep-link reply).
- `identity/InviteOutcome` — the reply to show the invitee + the (optional) holder-ping target/text; keeps the redeem logic free of any Telegram API dependency (the bot layer does the sends).
- `identity/ProfileClient` — WebClient → profile-service (`by-telegram`/create identity + `mintInvite`/`redeem` + `findById` for the inviter's Telegram id).
- `orchestrator/OrchestratorClient` — POST `/v1/intent`; plus `invoke` → POST `/v1/agents/invoke`, the hub's inter-agent action path, used for a front-door event with no sentence to classify (a scanned container label, IN-f1). Empty on 404 (target agent unregistered) so the caller degrades to a notice.
- `internal/InternalSendController` — `POST /internal/send`, Bearer-gated.
- `config/GatewayProperties`, `config/HttpClientsConfig`, `config/TelegramClientConfig` — `TelegramClient` exposed as a conditional bean so both `BotRegistration` and `InternalSendController` share it via `ObjectProvider`.
