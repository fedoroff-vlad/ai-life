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

## Configuration

| env var                          | default                              | required |
|----------------------------------|--------------------------------------|----------|
| `GATEWAY_PORT`                   | `8080`                               |          |
| `GATEWAY_TELEGRAM_BOT_USERNAME`  | `ai_life_bot`                        |          |
| `GATEWAY_TELEGRAM_BOT_TOKEN`     | *(empty — bot won't start)*          | yes for prod |
| `GATEWAY_DEFAULT_HOUSEHOLD_NAME` | `default household`                  |          |
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
Telegram bot API directly — the token stays here). Bearer-gated by
`GATEWAY_INTERNAL_API_TOKEN` (must match `INTERNAL_API_TOKEN` in notifier-service).
Body: [InternalSendRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/notify/InternalSendRequest.java).

## Key classes
- `GatewayApplication`.
- `bot/AiLifeBot` — Telegram bot impl. Intercepts a **callback query** (a confirm-button tap, #489 RU-2) before message dispatch — decodes it to "да"/"нет", answers the callback + strips the keyboard, routes it like a typed reply; then intercepts the family-invite commands before the normal media/text dispatch: `/start <token>` (redemption → invitee reply + holder ping) and `/invite <name> as <relationship>` (owner mint → deep-link reply, or usage on a bare `/invite`). Attaches the confirm keyboard to a binary-confirm reply (`isBinaryConfirm` → `pendingAction` hinted `PendingActionHints.CONFIRM`).
- `bot/ConfirmKeyboard` — the RU-2 shared inline-button primitive (#489; PX-4 will extend it): builds the two-button Да / Нет keyboard (localised labels, stable `cf:y`/`cf:n` callback ids) and decodes a tap's `callback_data` back into the "да"/"нет" text a route-locked `/resume` expects.
- `bot/TypingIndicator` — the RU-1 quick-ack (#489): `start(chatId)` fires a `sendChatAction=typing` now and refreshes it every ~4s on a daemon scheduler, returning a `Handle` (`AutoCloseable`) the bot closes when the reply is sent. Best-effort — every send is swallowed on failure so the typing hint never delays or breaks the reply. `AiLifeBot.consume` wraps the whole dispatch in `try (var t = typing.start(chatId))`.
- `bot/BotRegistration` — long-poll registration; no-ops when token is empty.
- `bot/MessageProcessor` — normalises Telegram updates into `NormalizedMessage`; uploads any photo/document/voice to media-service first and attaches the returned object id. For a captionless voice note it transcribes the uploaded audio and either routes the transcript as `text` or — when it's empty/low-confidence — returns the RU-3 ask-to-repeat reply without routing (`route` / `unintelligible`, threshold `gateway.stt.min-confidence`).
- `media/MediaServiceClient` — multipart `POST /v1/media` upload of media bytes → `MediaObjectDto`. Not soft-failed: for a media message the upload is the payload.
- `media/TranscribeClient` — `POST /internal/transcribe {mediaId}` against `mcp-media-processing` → the full `TranscriptResult` (text + `confidence` for the RU-3 gate). Front-door STT for voice notes; not soft-failed: the transcript is the voice message's payload.
- `identity/IdentityResolver` — `tg_user_id → User` (creates the user + their personal household on first contact, ADR-0001). Also `redeemInvite(...)` — a `/start <token>` join (resolve → redeem → resolve inviter → `InviteOutcome`) — and `mintInvite(...)` — the owner-side mint (resolve → `POST /v1/invites` → format the `t.me/<bot>?start=<token>` deep-link reply).
- `identity/InviteOutcome` — the reply to show the invitee + the (optional) holder-ping target/text; keeps the redeem logic free of any Telegram API dependency (the bot layer does the sends).
- `identity/ProfileClient` — WebClient → profile-service (`by-telegram`/create identity + `mintInvite`/`redeem` + `findById` for the inviter's Telegram id).
- `orchestrator/OrchestratorClient` — POST `/v1/intent`.
- `internal/InternalSendController` — `POST /internal/send`, Bearer-gated.
- `config/GatewayProperties`, `config/HttpClientsConfig`, `config/TelegramClientConfig` — `TelegramClient` exposed as a conditional bean so both `BotRegistration` and `InternalSendController` share it via `ObjectProvider`.
