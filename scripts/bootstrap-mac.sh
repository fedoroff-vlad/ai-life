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

# 4. Ollama as a background service (native Metal, on the host), with the runtime env it
#    needs for a 24/7 multi-agent stack. Idempotent: only reloads when a value actually changes.
#
#    Why this lives here and not in a hand-edited plist: Homebrew GENERATES
#    ~/Library/LaunchAgents/sh.brew.ollama.plist, so `brew services restart ollama` (or a formula
#    upgrade) silently discards anything added by hand. Pinning it in the bootstrap is what makes
#    the host reproducible — and it is why the reload below goes through launchctl, not
#    `brew services`, which would regenerate the file we just wrote.
#
#    OLLAMA_NUM_PARALLEL — the default is 1, and one user message costs 3–4 sequential model calls
#    (orchestrator classify → agent skill-route → agent synthesis, + embeddings). At 5–25 s a call
#    they queue, and on 2026-11-01 that blew calendar-agent's 60 s skill-routing budget on the very
#    first real smoke. model-strategy.md §One model for both tenants named both the risk and this
#    mitigation; this is it.
#    OLLAMA_CONTEXT_LENGTH — the model's own default is 262144, which held ~27 GB. 100000 is ample
#    for a household assistant and leaves the freed ~3.5 GB for the parallel slots' KV cache.
brew services start ollama >/dev/null 2>&1 || true

OLLAMA_PLIST="$HOME/Library/LaunchAgents/sh.brew.ollama.plist"
: "${OLLAMA_NUM_PARALLEL:=4}"
: "${OLLAMA_CONTEXT_LENGTH:=100000}"
if [[ -f "$OLLAMA_PLIST" ]]; then
  pb() { /usr/libexec/PlistBuddy -c "$1" "$OLLAMA_PLIST" 2>/dev/null; }
  ollama_env_changed=0
  for kv in "OLLAMA_NUM_PARALLEL=$OLLAMA_NUM_PARALLEL" "OLLAMA_CONTEXT_LENGTH=$OLLAMA_CONTEXT_LENGTH"; do
    k="${kv%%=*}"; v="${kv#*=}"
    [[ "$(pb "Print :EnvironmentVariables:$k")" == "$v" ]] && continue
    pb "Add :EnvironmentVariables:$k string $v" >/dev/null || pb "Set :EnvironmentVariables:$k $v" >/dev/null
    echo ">> ollama: $k=$v"
    ollama_env_changed=1
  done
  if [[ "$ollama_env_changed" == 1 ]]; then
    echo ">> reloading ollama via launchctl (brew services would regenerate the plist)"
    launchctl bootout "gui/$(id -u)" "$OLLAMA_PLIST" >/dev/null 2>&1 || true
    launchctl bootstrap "gui/$(id -u)" "$OLLAMA_PLIST" >/dev/null 2>&1 || true
    for _ in {1..20}; do
      curl -sf --max-time 3 http://localhost:11434/api/version >/dev/null 2>&1 && break
      sleep 2
    done
  fi
else
  echo ">> ollama plist not found — skipping runtime env (is ollama installed as a brew service?)"
fi

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
