#!/usr/bin/env bash
# One-command launch of the ai-life stack on the Mac (24/7). Idempotent — safe to re-run.
# Boots the always-on HOT set (LC-1 profiles). Cold services start on demand (by name, or
# `--profile cold` for a full smoke). Everything at once: add `--profile cold` below.
set -euo pipefail
cd "$(dirname "$0")/.."

# Guards: the stack needs infra/.env with a Telegram token (the only external entry point).
if [[ ! -f infra/.env ]]; then
  echo "!! infra/.env missing — run ./scripts/bootstrap-mac.sh first."
  exit 1
fi
if ! grep -qE '^GATEWAY_TELEGRAM_BOT_TOKEN=.+' infra/.env; then
  echo "!! GATEWAY_TELEGRAM_BOT_TOKEN is empty in infra/.env — fill it (see infra/.env.mac.example)."
  exit 1
fi

# 1. Inference engine (host, native Metal).
brew services start ollama >/dev/null 2>&1 || true

# 2. Hot set (first run builds every hot app image; ~5–10 min, then fast).
docker compose -f infra/docker-compose.yml --profile hot up -d --build

echo ""
echo "✅ ai-life up. Watch the entry point:"
echo "   docker compose -f infra/docker-compose.yml logs -f gateway-telegram"
echo "   Stop:  docker compose -f infra/docker-compose.yml down"
