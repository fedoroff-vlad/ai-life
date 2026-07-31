# gateway-telegram

Telegram entry point. Long-polling bot that:
1. Receives Telegram updates.
2. Resolves identity via `profile-service` — on first contact creates the user's **personal household**
   (named after them, ADR-0001) + user as `admin`, never attaching them to another user's household.
3. Builds a `NormalizedMessage` and sends it to `orchestrator`.
4. Replies to the chat with the orchestrator's response.

**Family invites — `/start <token>` (ADR-0001 slice 4b).** A deep-link `t.me/<bot>?start=<token>` opens
as the text `/start <token>`. The gateway intercepts it (not a routable message): it resolves/creates
the opener's identity, then redeems the invite via `profile-service`
(`POST /v1/invites/by-token/{token}/redeem`) so they join the inviter's family household, replies to the
invitee with a join confirmation, and DMs the **holder** (inviter) that they joined. An unknown /
already-used token is graceful — the opener just keeps their own isolated personal space. The owner-side
"mint an invite" command lands in slice 4b-ii.

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
- `bot/AiLifeBot` — Telegram bot impl. Intercepts `/start <token>` deep-links (family-invite redemption) before the normal media/text dispatch, sending the invitee reply + the holder ping.
- `bot/BotRegistration` — long-poll registration; no-ops when token is empty.
- `bot/MessageProcessor` — normalises Telegram updates into `NormalizedMessage`; uploads any photo/document/voice to media-service first and attaches the returned object id. For a captionless voice note it transcribes the uploaded audio (`transcribeIfVoice`) and routes the transcript as `text`.
- `media/MediaServiceClient` — multipart `POST /v1/media` upload of media bytes → `MediaObjectDto`. Not soft-failed: for a media message the upload is the payload.
- `media/TranscribeClient` — `POST /internal/transcribe {mediaId}` against `mcp-media-processing` → transcript text (front-door STT for voice notes). Not soft-failed: the transcript is the voice message's payload.
- `identity/IdentityResolver` — `tg_user_id → User` (creates the user + their personal household on first contact, ADR-0001). Also `redeemInvite(...)` — a `/start <token>` join: resolve identity → redeem → resolve the inviter's Telegram id, returning an `InviteOutcome`.
- `identity/InviteOutcome` — the reply to show the invitee + the (optional) holder-ping target/text; keeps the redeem logic free of any Telegram API dependency (the bot layer does the sends).
- `identity/ProfileClient` — WebClient → profile-service (`by-telegram`/create identity + `redeem` an invite + `findById` for the inviter's Telegram id).
- `orchestrator/OrchestratorClient` — POST `/v1/intent`.
- `internal/InternalSendController` — `POST /internal/send`, Bearer-gated.
- `config/GatewayProperties`, `config/HttpClientsConfig`, `config/TelegramClientConfig` — `TelegramClient` exposed as a conditional bean so both `BotRegistration` and `InternalSendController` share it via `ObjectProvider`.
