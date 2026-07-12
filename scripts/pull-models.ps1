# Pull the Ollama models ai-life uses. Idempotent - ollama skips models already present.
# ~26 GB total. Kept in sync with infra/.env.mac.example.
$ErrorActionPreference = 'Stop'

$models = @(
    'qwen2.5:32b',       # chat, default quality
    'qwen2.5:14b',       # downshift when the coder is active
    'qwen2.5:7b',        # FAST channel - routing + safety
    'minicpm-v',         # vision / OCR (cold, on demand)
    'nomic-embed-text'   # embeddings (memory recall)
)

foreach ($m in $models) {
    Write-Host ">> ollama pull $m"
    ollama pull $m
}

Write-Host "OK models ready: $($models -join ', ')"
