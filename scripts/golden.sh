#!/usr/bin/env bash
#
# Run the Stage-5 golden tests (@GoldenLlmTest, #199) against a real model with ONE command.
#
# It ensures a warm, Ollama-backed llm-gateway on :8081, then runs the mvn args you pass with
# GOLDEN_LLM=true. The gateway is left running (nohup), so the *next* invocation skips startup
# entirely — the launch is then instant. Idempotent: a gateway already healthy on the port is reused.
#
# Prereqs: Ollama up on :11434 with qwen2.5:7b + nomic-embed-text (`ollama serve`, `ollama list`).
#
# Usage:
#   scripts/golden.sh -pl domains/knowledge/notes-agent -Dtest='GoldenNoteWriterTest,GoldenNoteFinderTest'
#   scripts/golden.sh -pl platform/orchestrator -Dtest=GoldenRoutingTest
#   scripts/golden.sh down     # stop the background gateway when you're done
#
# Notes:
# - Resolves the JDK via $JAVA_HOME/bin/java when `java` isn't on PATH (the default on a bare Git Bash
#   shell on Windows). Set $JAVA to override.
# - Gateway log: logs/llm-gateway-golden.log. Override the port with LLM_GATEWAY_PORT.
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root, wherever this is invoked from

PORT="${LLM_GATEWAY_PORT:-8081}"
URL="http://localhost:${PORT}"
JAR="platform/llm-gateway/target/llm-gateway.jar"
LOG="logs/llm-gateway-golden.log"

java_bin() {
  if [ -n "${JAVA:-}" ]; then echo "$JAVA"; return; fi
  if command -v java >/dev/null 2>&1; then echo "java"; return; fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  echo "ERROR: no 'java' on PATH and \$JAVA_HOME is unset — set \$JAVA or \$JAVA_HOME" >&2
  exit 1
}

gateway_up() { curl -sf --max-time 3 "$URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }

port_pids() {
  # Windows (Git Bash) via netstat; POSIX via lsof — whichever exists.
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$PORT" -s tcp:LISTEN 2>/dev/null || true
  else
    netstat -ano 2>/dev/null | grep -E ":$PORT .*LISTENING" | awk '{print $NF}' | sort -u || true
  fi
}

stop_gateway() {
  local pids; pids="$(port_pids)"
  if [ -z "$pids" ]; then echo "no llm-gateway listening on :$PORT"; return; fi
  for pid in $pids; do
    if command -v taskkill >/dev/null 2>&1; then taskkill //PID "$pid" //F >/dev/null 2>&1 || true
    else kill "$pid" 2>/dev/null || true; fi
  done
  echo "stopped llm-gateway on :$PORT (pids: $pids)"
}

if [ "${1:-}" = "down" ]; then stop_gateway; exit 0; fi

if [ "$#" -eq 0 ]; then
  echo "usage: scripts/golden.sh -pl <module> -Dtest=<GoldenTest[,GoldenTest2]>   (or: scripts/golden.sh down)" >&2
  exit 2
fi

# 1) Ollama must be up — the gateway is just a thin adapter in front of it.
if ! curl -sf --max-time 3 http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "ERROR: Ollama not reachable on :11434 — start it (\`ollama serve\`) and pull qwen2.5:7b + nomic-embed-text" >&2
  exit 1
fi

# 2) Ensure a warm gateway. Already healthy → instant reuse; otherwise start it once and wait.
if gateway_up; then
  echo "llm-gateway already warm on $URL — skipping startup"
else
  mkdir -p logs
  [ -f "$JAR" ] || mvn -q -pl platform/llm-gateway -am -DskipTests package
  echo "starting llm-gateway on $URL (Ollama qwen2.5:7b) …"
  LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
  LLM_DEFAULT_MODEL=qwen2.5:7b LLM_FAST_MODEL=qwen2.5:7b \
  LLM_EMBEDDING_MODEL=nomic-embed-text LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT="$PORT" \
    nohup "$(java_bin)" -jar "$JAR" > "$LOG" 2>&1 &
  disown || true
  for _ in $(seq 1 60); do gateway_up && break; sleep 2; done
  gateway_up || { echo "ERROR: gateway did not become healthy — see $LOG" >&2; exit 1; }
  echo "llm-gateway healthy."
fi

# 3) Run the golden tests against the warm gateway.
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL="$URL" mvn "$@" test
