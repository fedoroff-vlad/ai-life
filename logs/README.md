# logs/ — ad-hoc captured outputs (gitignored)

Bucket for hand-captured command output: `mvn ... > logs/<name>.log`, CI
debugging dumps, anything you piped to a file to read later. **The folder
itself is gitignored** (`.gitignore` → `logs/`), so anything you drop here
stays local.

## Convention
- Capture full Maven output to `logs/mvn-<scope>.log` (e.g. `mvn-finance-agent.log`, `mvn-reactor.log`).
- PR-specific captures: `logs/pr<NN>-<topic>.log` (e.g. `pr35-full.log`).
- Never paste the whole file into a PR description or commit message — extract
  the failing assertion + ~3 relevant lines (per [CLAUDE.md](../CLAUDE.md)
  §"Test strategy").

## Why a folder instead of just `*.log` everywhere
- Discoverability — one place to look for "what did I capture last week".
- Easier cleanup (`rm -rf logs/*` is unambiguous; `*.log` could nuke an
  intentional Liquibase changelog with a `.log` extension someday).
- Keeps the repo root clean.
