#!/usr/bin/env bash
# One-command macOS setup for ai-life (24/7 host deployment). Idempotent — safe to re-run.
#   ./scripts/bootstrap-mac.sh               # everything (pulls models — ~26 GB)
#   SKIP_MODELS=1 ./scripts/bootstrap-mac.sh  # tools only
set -euo pipefail
cd "$(dirname "$0")/.."

# 1. Xcode Command Line Tools — git + compilers Homebrew needs.
if ! xcode-select -p >/dev/null 2>&1; then
  echo ">> Installing Xcode Command Line Tools…"
  xcode-select --install || true
  echo "   Finish the CLT installer dialog, then re-run this script."
  exit 1
fi

# 2. Homebrew.
if ! command -v brew >/dev/null 2>&1; then
  echo ">> Installing Homebrew…"
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  eval "$(/opt/homebrew/bin/brew shellenv)"
fi

# 3. Tools + apps (installs only what's missing).
echo ">> brew bundle…"
brew bundle --file Brewfile || echo ">> some Brewfile entries need attention (e.g. WireGuard = App Store sign-in) — continuing"

# 4. Ollama as a background service (native Metal, on the host).
brew services start ollama >/dev/null 2>&1 || true

# 5. Seed infra/.env (secrets stay for the user to fill).
if [[ ! -f infra/.env ]]; then
  cp infra/.env.example infra/.env
  echo ">> created infra/.env from .env.example"
fi

# 6. Models — all of them (running is on demand). Skip with SKIP_MODELS=1.
if [[ "${SKIP_MODELS:-0}" != "1" ]]; then
  echo ">> pulling models (~26 GB; skip with SKIP_MODELS=1)…"
  ./scripts/pull-models.sh
fi

echo ""
echo "✅ ai-life tools ready. Before launch:"
echo "   1) apply infra/.env.mac.example into infra/.env (LLM block) + fill the 4 secrets"
echo "   2) ./scripts/start-mac.sh"
