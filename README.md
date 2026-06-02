# ai-life

Personal AI agents system for a 2-person household.
Telegram entry → orchestrator → domain agents → narrow MCP servers → Postgres.
Local-first deployment (target: Mac Studio 128 GB), Java / Spring Boot.

> **Status:** Stage 0 — foundation in progress. See `CLAUDE.md` for working conventions and authorization rules.

## Stack
- Java 21 LTS, Maven 3.9+, Spring Boot 3.4.x, Spring AI (MCP).
- Postgres 16 + pgvector + Apache AGE + pg_trgm.
- Liquibase (XML master, YAML features, raw SQL for complex DDL).
- Docker Compose for local infra. GitHub Actions for CI.

## Layout
```
ai-life/
├── pom.xml                          parent POM (versions, plugin mgmt, BOMs)
├── libs/                            shared libraries (jars consumed by services)
│   ├── contracts/                   DTOs, JSON Schemas, NormalizedMessage
│   ├── llm-client/                  channel-based client for llm-gateway
│   ├── mcp-client/                  wrapper around Spring AI MCP
│   ├── event-bus/                   Postgres LISTEN/NOTIFY adapter
│   └── platform-common/             logging, metrics, error envelopes
├── infra/                           docker-compose, liquibase, init scripts (PR2)
├── platform/                        gateway, orchestrator, llm-gateway, ...  (PR3+)
├── agents/                          calendar, finance, tasks                  (Stage 1+)
├── mcp/                             caldav, finance, tasks, media-processing  (Stage 1+)
└── skills/                          SKILL.md per skill (Anthropic Skills format)
```

## Build
```sh
mvn -B verify
```

## Plan
Full architecture and roadmap: `C:\Users\vlad\.claude\plans\fluffy-sparking-sunset.md` (local plan file).
