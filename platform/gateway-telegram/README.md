# gateway-telegram

Telegram entry point. Long-polling bot that:
1. Receives Telegram updates.
2. Resolves identity via `profile-service` (creates household + user on first contact).
3. Builds a `NormalizedMessage` and sends it to `orchestrator`.
4. Replies to the chat with the orchestrator's response.

Photo messages are supported: the largest variant is downloaded and uploaded to `media-service`,
and the returned object id rides on the `NormalizedMessage` as an `image` attachment
(`storageUri` = the media object id; the caption becomes `text`). A downstream agent fetches the
bytes back from media-service by that id. Audio / video / documents land later alongside
`mcp-media-processing`.

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

Then DM your bot. The first message creates your household and you as `admin`.

## `POST /internal/send`

Internal-only outbound channel for notifier-service (notifier never touches the
Telegram bot API directly — the token stays here). Bearer-gated by
`GATEWAY_INTERNAL_API_TOKEN` (must match `INTERNAL_API_TOKEN` in notifier-service).
Body: [InternalSendRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/notify/InternalSendRequest.java).

## Key classes
- `GatewayApplication`.
- `bot/AiLifeBot` — Telegram bot impl.
- `bot/BotRegistration` — long-poll registration; no-ops when token is empty.
- `bot/MessageProcessor` — normalises Telegram updates into `NormalizedMessage`; uploads any photo to media-service first and attaches the returned object id.
- `media/MediaServiceClient` — multipart `POST /v1/media` upload of photo bytes → `MediaObjectDto`. Not soft-failed: for a photo message the upload is the payload.
- `identity/IdentityResolver` — `tg_user_id → User` (creates user + household on first contact).
- `identity/ProfileClient` — WebClient → profile-service.
- `orchestrator/OrchestratorClient` — POST `/v1/intent`.
- `internal/InternalSendController` — `POST /internal/send`, Bearer-gated.
- `config/GatewayProperties`, `config/HttpClientsConfig`, `config/TelegramClientConfig` — `TelegramClient` exposed as a conditional bean so both `BotRegistration` and `InternalSendController` share it via `ObjectProvider`.
