# One-command Windows setup for ai-life (local development / running the stack). Idempotent.
#   .\scripts\bootstrap-win.ps1                        # everything (pulls models - ~26 GB)
#   $env:SKIP_MODELS = '1'; .\scripts\bootstrap-win.ps1 # tools only
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

# 1. winget (ships with Windows 11 as "App Installer").
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw "winget not found. Install 'App Installer' from the Microsoft Store, then re-run."
}

# 2. Tools + apps (installs only what's missing).
Write-Host ">> winget import..."
winget import -i winget-packages.json --accept-source-agreements --accept-package-agreements --ignore-unavailable

# Refresh PATH so tools just installed (ollama, mvn, ...) are usable in this same session.
$env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
            [System.Environment]::GetEnvironmentVariable('Path', 'User')

# 3. Seed infra/.env (secrets stay for the user to fill).
if (-not (Test-Path infra\.env)) {
    Copy-Item infra\.env.example infra\.env
    Write-Host ">> created infra/.env from .env.example"
}

# 4. Models - all of them (running is on demand). Skip with SKIP_MODELS=1.
if ($env:SKIP_MODELS -ne '1') {
    Write-Host ">> pulling models (~26 GB; set `$env:SKIP_MODELS='1' to skip)..."
    & "$PSScriptRoot\pull-models.ps1"
}

Write-Host "`nOK ai-life tools ready. Before launch:"
Write-Host "   1) apply infra/.env.mac.example into infra/.env (LLM block) + fill the 4 secrets"
Write-Host "   2) .\scripts\start-win.ps1"
