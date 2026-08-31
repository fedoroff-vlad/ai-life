# AGENTS.md

Entry point for AI coding agents working in this repo, per the [agents.md](https://agents.md)
convention. This file is a **pointer, not a second source of truth** — the authoritative agent
instructions live in [`CLAUDE.md`](CLAUDE.md), and duplicating them here would only invite drift.

**Read [`CLAUDE.md`](CLAUDE.md) first.** It defines the fixed session reading order, the branch → PR →
green-CI → squash-merge workflow, the change-propagation and README-upkeep rules, the test strategy
(fast `mvn test` vs `mvn verify`), and the authorization boundaries. Everything an agent needs to work
here safely is there or linked from there.

Fast map of the rest:

- **What to build / where things live** — [`plans/INDEX.md`](plans/INDEX.md) (read this, then the ONE
  relevant `plans/<domain>.md`), then the target module's `README.md`.
- **Current in-flight work + next steps** — [`plans/STATUS.md`](plans/STATUS.md).
- **Architecture, conventions, locked decisions** — [`plans/architecture.md`](plans/architecture.md).
- **Reusable dev-workflow skills** — [`tools/agent-skills/`](tools/agent-skills) (submodule).

The project is a personal AI-agents system (Telegram → orchestrator → domain agents → narrow MCP
servers → Postgres), Java 25 / Spring Boot 4 / Maven monorepo. Build/run details are in
[`infra/README.md`](infra/README.md); consistency lints in
[`scripts/check-consistency.sh`](scripts/check-consistency.sh) run on every PR.
