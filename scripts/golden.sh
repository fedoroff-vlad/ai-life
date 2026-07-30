#!/usr/bin/env bash
#
# Run the Stage-5 golden tests (@GoldenLlmTest, #199) against a real model with ONE command.
#
# Two profiles, selected by GOLDEN_PROFILE (default: ollama):
#   ollama  — the free local stack: Ollama on :11434 + an Ollama-backed llm-gateway on :8081.
#   openai  — an OpenAI-compatible gateway supplied entirely by env (the work dev-validator, #359):
#             no Ollama, no local model — the gateway just proxies to $GOLDEN_OPENAI_BASE_URL. Runs
#             on :8091 by default so it can coexist with a warm ollama-profile gateway on :8081.
#
# It brings up whatever the profile needs, then runs the mvn args you pass with GOLDEN_LLM=true. What
# it started is left running, so the *next* invocation skips startup entirely and the launch is
# instant. Idempotent: anything already healthy is reused.
#
# Usage:
#   scripts/golden.sh -pl domains/knowledge/notes-agent -Dtest='GoldenNoteWriterTest,GoldenNoteFinderTest'
#   scripts/golden.sh -pl platform/orchestrator -Dtest=GoldenRoutingTest
#   GOLDEN_PROFILE=openai scripts/golden.sh -pl platform/orchestrator -Dtest=GoldenRoutingTest
#   scripts/golden.sh down                          # stop the ollama-profile stack (gateway + Ollama, if this script started it)
#   GOLDEN_PROFILE=openai scripts/golden.sh down    # stop the openai-profile gateway on :8091
#
# openai profile — all employer specifics via ENV ONLY (never commit a hostname/model — scrub-identity):
#   GOLDEN_OPENAI_BASE_URL           (required) the gateway's OpenAI /v1 base URL, e.g. https://host/v1
#   GOLDEN_OPENAI_MODEL              (required) chat model to validate against (default + fast channel)
#   GOLDEN_OPENAI_API_KEY           (optional) sent as Authorization: Bearer <key> when set
#   GOLDEN_OPENAI_FAST_MODEL        (optional) fast-channel model; defaults to GOLDEN_OPENAI_MODEL
#   GOLDEN_OPENAI_EMBEDDING_MODEL   (optional) embedding model; defaults to nomic-embed-text
#   GOLDEN_OPENAI_SUPPRESS_THINKING (optional) send reasoning_effort:none; defaults to false (hosted models don't need it)
#   GOLDEN_OPENAI_TIMEOUT_SECONDS   (optional) upstream chat/embed timeout; defaults to 180
#
# Notes:
# - Auto-starts `ollama serve` only if :11434 isn't already answering; a pre-existing Ollama daemon is
#   reused and left alone. The required models (qwen3:8b + nomic-embed-text) must be pulled already —
#   the script won't download multi-GB blobs behind your back; it tells you the `ollama pull` to run.
# - qwen3 is a *thinking* model: the gateway is started with LLM_SUPPRESS_THINKING=true so it sends
#   `reasoning_effort:none` in the request body (see llm-gateway/README §Golden tests). Without it a
#   routing call runs ~144s instead of ~4s on CPU and blows the tests' 90s block timeout. NB the
#   `/no_think` prompt tag does NOT work through Ollama's /v1 — only the body field does.
# - Resolves the JDK via $JAVA_HOME/bin/java when `java` isn't on PATH (the default on a bare Git Bash
#   shell on Windows). Set $JAVA to override.
# - Logs: logs/ollama-golden.log, logs/llm-gateway-golden.log. Override the port with LLM_GATEWAY_PORT.
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root, wherever this is invoked from

PROFILE="${GOLDEN_PROFILE:-ollama}"
case "$PROFILE" in
  ollama|openai) ;;
  *) echo "ERROR: unknown GOLDEN_PROFILE '$PROFILE' (expected: ollama | openai)" >&2; exit 2 ;;
esac

# Each profile defaults to its own port so an ollama gateway (:8081) and an openai one (:8082) can
# stay warm side by side; LLM_GATEWAY_PORT still overrides.
DEFAULT_PORT=8081
[ "$PROFILE" = openai ] && DEFAULT_PORT=8091   # 8082 is profile-service; keep the openai golden gateway clear of it
PORT="${LLM_GATEWAY_PORT:-$DEFAULT_PORT}"
URL="http://localhost:${PORT}"
OLLAMA_URL="http://localhost:11434"
JAR="platform/llm-gateway/target/llm-gateway.jar"
GATEWAY_LOG="logs/llm-gateway-golden.log"
OLLAMA_LOG="logs/ollama-golden.log"
OLLAMA_PIDFILE="logs/.ollama-golden.pid"   # only written when THIS script starts Ollama
REQUIRED_MODELS="qwen3:8b nomic-embed-text"

java_bin() {
  if [ -n "${JAVA:-}" ]; then echo "$JAVA"; return; fi
  if command -v java >/dev/null 2>&1; then echo "java"; return; fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  echo "ERROR: no 'java' on PATH and \$JAVA_HOME is unset — set \$JAVA or \$JAVA_HOME" >&2
  exit 1
}

ollama_up() { curl -sf --max-time 3 "$OLLAMA_URL/api/tags" >/dev/null 2>&1; }
gateway_up() { curl -sf --max-time 3 "$URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }

port_pids() {
  # Windows (Git Bash) via netstat; POSIX via lsof — whichever exists.
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$PORT" -s tcp:LISTEN 2>/dev/null || true
  else
    netstat -ano 2>/dev/null | grep -E ":$PORT .*LISTENING" | awk '{print $NF}' | sort -u || true
  fi
}

kill_pid() {
  local pid="$1"
  if command -v taskkill >/dev/null 2>&1; then taskkill //PID "$pid" //F >/dev/null 2>&1 || true
  else kill "$pid" 2>/dev/null || true; fi
}

ensure_ollama() {
  if ollama_up; then return; fi
  command -v ollama >/dev/null 2>&1 || {
    echo "ERROR: Ollama not running on :11434 and the \`ollama\` CLI isn't on PATH — install Ollama first" >&2
    exit 1
  }
  mkdir -p logs
  echo "starting ollama serve on :11434 …"
  nohup ollama serve > "$OLLAMA_LOG" 2>&1 &
  echo "$!" > "$OLLAMA_PIDFILE"
  disown || true
  for _ in $(seq 1 30); do ollama_up && break; sleep 1; done
  ollama_up || { echo "ERROR: Ollama did not come up — see $OLLAMA_LOG" >&2; exit 1; }
  echo "ollama healthy."
}

check_models() {
  local have; have="$(curl -sf --max-time 5 "$OLLAMA_URL/api/tags" 2>/dev/null || true)"
  for m in $REQUIRED_MODELS; do
    case "$have" in
      *"\"$m\""*) ;;   # present (name appears as "qwen3:8b" or "nomic-embed-text:latest")
      *"\"$m:"*) ;;
      *) echo "ERROR: Ollama model '$m' not pulled — run: ollama pull $m" >&2; exit 1 ;;
    esac
  done
}

ensure_gateway() {
  if gateway_up; then
    echo "llm-gateway already warm on $URL — skipping startup"
    return
  fi
  mkdir -p logs
  [ -f "$JAR" ] || mvn -q -pl platform/llm-gateway -am -DskipTests package
  echo "starting llm-gateway on $URL (Ollama qwen3:8b, thinking suppressed) …"
  LLM_PROVIDER=openai-compatible LLM_BASE_URL="$OLLAMA_URL/v1" \
  LLM_DEFAULT_MODEL=qwen3:8b LLM_FAST_MODEL=qwen3:8b LLM_SUPPRESS_THINKING=true \
  LLM_EMBEDDING_MODEL=nomic-embed-text LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT="$PORT" \
    nohup "$(java_bin)" -jar "$JAR" > "$GATEWAY_LOG" 2>&1 &
  disown || true
  for _ in $(seq 1 60); do gateway_up && break; sleep 2; done
  gateway_up || { echo "ERROR: gateway did not become healthy — see $GATEWAY_LOG" >&2; exit 1; }
  echo "llm-gateway healthy."
}

ensure_gateway_openai() {
  # All employer specifics come from env — nothing about the work gateway is written into the repo.
  : "${GOLDEN_OPENAI_BASE_URL:?set GOLDEN_OPENAI_BASE_URL to the work gateway OpenAI /v1 base URL}"
  : "${GOLDEN_OPENAI_MODEL:?set GOLDEN_OPENAI_MODEL to the chat model to validate against}"
  if gateway_up; then
    echo "llm-gateway already warm on $URL — skipping startup"
    return
  fi
  mkdir -p logs
  [ -f "$JAR" ] || mvn -q -pl platform/llm-gateway -am -DskipTests package
  echo "starting llm-gateway on $URL (openai-compatible → work gateway) …"
  LLM_PROVIDER=openai-compatible LLM_BASE_URL="$GOLDEN_OPENAI_BASE_URL" \
  LLM_API_KEY="${GOLDEN_OPENAI_API_KEY:-}" \
  LLM_DEFAULT_MODEL="${GOLDEN_OPENAI_MODEL}" LLM_FAST_MODEL="${GOLDEN_OPENAI_FAST_MODEL:-$GOLDEN_OPENAI_MODEL}" \
  LLM_EMBEDDING_MODEL="${GOLDEN_OPENAI_EMBEDDING_MODEL:-nomic-embed-text}" \
  LLM_SUPPRESS_THINKING="${GOLDEN_OPENAI_SUPPRESS_THINKING:-false}" \
  LLM_REQUEST_TIMEOUT_SECONDS="${GOLDEN_OPENAI_TIMEOUT_SECONDS:-180}" LLM_GATEWAY_PORT="$PORT" \
    nohup "$(java_bin)" -jar "$JAR" > "$GATEWAY_LOG" 2>&1 &
  disown || true
  for _ in $(seq 1 60); do gateway_up && break; sleep 2; done
  gateway_up || { echo "ERROR: gateway did not become healthy — see $GATEWAY_LOG" >&2; exit 1; }
  echo "llm-gateway healthy."
}

stop_stack() {
  local pids; pids="$(port_pids)"
  if [ -n "$pids" ]; then for pid in $pids; do kill_pid "$pid"; done; echo "stopped llm-gateway on :$PORT (pids: $pids)"; else echo "no llm-gateway listening on :$PORT"; fi
  # The openai profile never touches Ollama — only the ollama profile owns a daemon it may have started.
  if [ "$PROFILE" = openai ]; then return; fi
  if [ -f "$OLLAMA_PIDFILE" ]; then
    local opid; opid="$(cat "$OLLAMA_PIDFILE")"
    kill_pid "$opid"; rm -f "$OLLAMA_PIDFILE"
    echo "stopped ollama (pid $opid) — it was started by this script"
  else
    echo "left Ollama running (not started by this script)"
  fi
}

if [ "${1:-}" = "down" ]; then stop_stack; exit 0; fi

if [ "$#" -eq 0 ]; then
  echo "usage: [GOLDEN_PROFILE=ollama|openai] scripts/golden.sh -pl <module> -Dtest=<GoldenTest[,GoldenTest2]>   (or: scripts/golden.sh down)" >&2
  exit 2
fi

if [ "$PROFILE" = openai ]; then
  ensure_gateway_openai
else
  ensure_ollama
  check_models
  ensure_gateway
fi

# Run the golden tests against the warm gateway.
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL="$URL" mvn "$@" test
