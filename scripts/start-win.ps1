# One-command launch of the ai-life stack on Windows. Idempotent - safe to re-run.
# Boots the always-on HOT set (LC-1 profiles). Cold services start on demand (by name, or
# `--profile cold` for a full smoke). Everything at once: add `--profile cold` below.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

# Guards: the stack needs infra/.env with a Telegram token (the only external entry point).
if (-not (Test-Path infra\.env)) {
    throw "infra/.env missing - run .\scripts\bootstrap-win.ps1 first."
}
if (-not (Select-String -Path infra\.env -Pattern '^GATEWAY_TELEGRAM_BOT_TOKEN=.+' -Quiet)) {
    throw "GATEWAY_TELEGRAM_BOT_TOKEN is empty in infra/.env - fill it (see infra/.env.mac.example)."
}

# Docker must be running (Docker Desktop).
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker isn't running - start Docker Desktop, then re-run."
}

# Hot set (first run builds every hot app image; ~5-10 min, then fast).
docker compose -f infra/docker-compose.yml --profile hot up -d --build

Write-Host "`nOK ai-life up. Watch the entry point:"
Write-Host "   docker compose -f infra/docker-compose.yml logs -f gateway-telegram"
Write-Host "   Stop:  docker compose -f infra/docker-compose.yml down"
