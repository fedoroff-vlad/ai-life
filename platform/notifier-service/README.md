# notifier-service

Resolves an internal `userId` to a `telegram_user_id` (via profile-service) and forwards
the payload to gateway-telegram's `/internal/send`. The Telegram bot token never leaves
gateway-telegram.

## Endpoints

| method | path        | purpose                                          |
|--------|-------------|--------------------------------------------------|
| POST   | `/v1/notify`| `{ userId: UUID, text: string }` → 202/404/422   |

## Configuration (env vars)

```
NOTIFIER_PORT=8084
PROFILE_SERVICE_URL=http://profile-service:8082
GATEWAY_TELEGRAM_URL=http://gateway-telegram:8080
INTERNAL_API_TOKEN=<shared with gateway-telegram>
```

## Status codes

- `202` — accepted and forwarded successfully
- `400` — bad request (missing fields)
- `404` — user not found
- `422` — user has no telegram_user_id linked yet

## Key classes
- `NotifierApplication`.
- `config/NotifierProperties` — `notifier.{profile-base-url, gateway-base-url, internal-api-token}`.
- `config/HttpConfig` — separate WebClients for profile + gateway (each via `.clone()` to avoid shared-builder leakage).
- `web/NotifyController` — resolves user, forwards to gateway-telegram's `/internal/send`.

## Failure mode
Per-user notifier failures are caller-swallowed by upstream fan-outs (one bad user
doesn't block the rest). See `TriggerController` in [calendar-agent](../../agents/calendar-agent)
for the loop that exploits this — it logs + continues when a single member's notify
POST fails.
