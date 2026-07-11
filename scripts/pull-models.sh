#!/usr/bin/env bash
# Pull the Ollama models ai-life uses (host, native Metal). Idempotent — ollama skips models
# already present. ~26 GB total. Kept in sync with infra/.env.mac.example.
set -euo pipefail

MODELS=(
  "qwen2.5:32b"       # chat, default quality           ~20 GB
  "qwen2.5:14b"       # downshift when the coder is active ~9 GB
  "qwen2.5:7b"        # FAST channel — routing + safety   ~5 GB
  "minicpm-v"         # vision / OCR (cold, on demand)    ~5.5 GB
  "nomic-embed-text"  # embeddings (memory recall)        ~0.3 GB
)

for m in "${MODELS[@]}"; do
  echo ">> ollama pull $m"
  ollama pull "$m"
done

echo "✅ models ready: ${MODELS[*]}"
