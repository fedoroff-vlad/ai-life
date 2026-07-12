# Pull the Ollama models ai-life uses. Idempotent - ollama skips models already present.
# ~26 GB total. Kept in sync with infra/.env.mac.example.
$ErrorActionPreference = 'Stop'

$models = @(
    'qwen3:32b',         # chat, default quality (~20 GB)
    'qwen3:14b',         # downshift when the coder is active (~9.3 GB)
    'qwen3:8b',          # FAST channel - routing + safety (~5.2 GB)
    'minicpm-v',         # vision / OCR (cold, on demand)
    'nomic-embed-text'   # embeddings (memory recall)
)

foreach ($m in $models) {
    Write-Host ">> ollama pull $m"
    ollama pull $m
}

Write-Host "OK models ready: $($models -join ', ')"
