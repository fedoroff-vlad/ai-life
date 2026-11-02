# domain-mcp-host

**Status (2026-11-02):** #584 slices 3n + 3o + 3p — the **resident Domain-MCP-hot** host, now the
**deployed topology**: it replaces the five `mcp-*` services in `infra/docker-compose.yml` (hot set
28 → 24), exposing their old names as network aliases so no `MCP_*_URL` changed. ADR-0006 Path B
(process consolidation), approach B1.

Boots the always-on `Domain-MCP-hot` module contexts **in one JVM**, each an independent Spring context
on its own port with its own MCP server — so agents keep calling each MCP's `/internal/*` on the same
URL/port and every contract is unchanged. The win is that the ~300 MB per-process JVM baseline
(JIT/code-cache, metaspace, GC, thread pools) is paid **once per host** instead of once per module.

The resident hot set ([plans/topology-map.md](../../plans/topology-map.md) §Resident):
`mcp-caldav · mcp-finance · mcp-tasks · mcp-web · mcp-media-processing`.

Depends on each module's **plain classes jar** (enabled by #584 slice 3a, which moved every co-hosted
module's executable to the `-exec` classifier).

## How the config collision is solved (slice 3n)
Every module's configuration sits at `classpath:/application.yml`, so on the host's shared classloader
five contexts would fight over one file. Slices 3b…3c sidestepped that with a unique, **non-existent**
`spring.config.name`, which made each context ignore *all* of its own configuration — workable for an IT
that passes properties explicitly, but not for a deployable process: the yml is where each module's
`env → property` mapping lives (`MCP_CALDAV_PORT`, `MCP_CALDAV_DB_URL`, `SCHEDULER_URL`, the OCR/STT
engine picks …) **and** where `spring.ai.mcp.server.instructions` lives — the tool-surface prose the MCP
server hands its clients.

So instead each co-hosted module's **pom** lands a copy of its own `application.yml` at
`config/<module>/` (`<targetPath>`), and the launcher boots each context with
`spring.config.location=classpath:/config/<module>/`:
- **One source file, two classpath locations.** The default `classpath:/application.yml` is kept, so a
  standalone `-exec.jar` resolves its config exactly as before — co-hosting is invisible to the separate
  processes. Nothing is kept in sync by hand.
- **Env substitution keeps working**, so the per-module env vars the compose services already set need no
  translation layer in Java.
- **Not `optional:`** — a module whose pom loses the resource block fails its context at startup instead
  of silently booting on a sibling's configuration.

Same precedent as slice 3d's per-agent `manifest/<name>/AGENT.md`: the file stays where it belongs in the
module; only its classpath location changes.

## Co-hosting merges classpaths — and that changes what auto-configures (slice 3o)
Five modules in one JVM means **one classpath**. A capability MCP that owns no schema (`mcp-web`,
`mcp-media-processing`) standalone has no JPA on its classpath at all; co-hosted it inherits its
schema-bound siblings' starters, and Boot then auto-configures a `DataSource` it has no URL for:

```
Failed to determine a suitable driver class
APPLICATION FAILED TO START
```

That is exactly how this host's first deploy died — three contexts up, the fourth dead — **while the IT
was green**, because the IT handed a Testcontainers datasource to every context, capability MCPs
included. So `Hosted` records each member's `Persistence`, a `SCHEMA_LESS` member is booted with the
JPA/DataSource auto-configurations excluded, and the IT now gives a datasource only to `SCHEMA_BOUND`
members — it reproduces deploy instead of papering over it.

Adding a member means deciding its `Persistence`. Getting it wrong fails that context at startup.

## Running it
It is part of the hot profile — the normal bring-up starts it:

```
docker compose -f infra/docker-compose.yml --profile hot up -d
```

One container, one JVM, five contexts, each on the port its own yml declares. The healthcheck probes
**all five** ports: slice 3o's deploy came up with three contexts live, and a single-port probe would
have called that healthy and let every dependent start against a host missing two MCPs.

The image carries the **union** of its members' native dependencies — today only
`mcp-media-processing`'s tesseract (`ocr`) and ffmpeg (`frames`); slice 3l adds `yt-dlp`. The container
needs the union of the five services' env vars, which is conflict-free today (`SCHEDULER_URL` is the
only name two members share, with the same value) — that is a property to re-check when a member joins,
not a guarantee.

Because the five service **names** are how 14 services address these MCPs
(`MCP_CALDAV_URL: http://mcp-caldav:8090`), the host takes those names as compose **network aliases**;
the URLs resolve to this container unchanged. Verified on the running stack: from inside
`calendar-agent`, all five old `name:port` pairs answer `HTTP 200`.

**Keep the alias list equal to `RESIDENT_HOT`.** They are one coupling in two files and nothing else
reads both — a member without its alias is unreachable fleet-wide while every healthcheck stays green.
`scripts/check-consistency.sh` **check 18** enforces it.

Rollback is `git revert` of the cutover: the five module Dockerfiles and their `-exec` jars are
untouched, so the separate services still build and run.

## Logging identity
The logging system initialises once per JVM, so the **first** member's `spring.application.name` would
prefix every context's lines — five contexts all logging as `[mcp-caldav]`, which is why slice 3o's
failure first read as a caldav fault when the dead context was `mcp-web`. The launcher therefore boots
with `logging.include-application-name=false`: better to drop the field than let four lines in five lie.
Contexts stay distinguishable by logger name (`d.f.ailife.mcp.web…`).

## Key classes
- `DomainMcpHost` — the launcher. `RESIDENT_HOT` lists the co-hosted modules with each member's
  `Persistence`; `Hosted.configLocation()` is the module's own config path;
  `Hosted.excludedAutoConfigurations()` is what co-hosting must switch off for it. `boot(Hosted,
  overrides)` starts one context and `main` is the deploy entry point; **both** assemble their arguments
  through the one private `launchArgs` helper — when the test path and the deploy path each built their
  own wiring, the IT passed and the deploy died. Overrides go in as **command-line args** because Boot's
  builder properties rank *below* config files, so a caller's value would otherwise lose to the yml.
  Cold hosts (Content, Lifestyle, Brief+Travel, …) each get their own launcher list.

## Tests
- `DomainMcpHostFootprintIntegrationTest` (`it`) — boots all five resident contexts in one JVM against a
  Testcontainers PG and asserts co-residency: all live, five distinct ports, each with only its own
  application bean, **each on its own `application.yml`** (its own `spring.application.name`, non-blank
  `spring.ai.mcp.server.instructions`). Ports are overridden to ephemeral so the IT cannot collide with a
  running stack or a sibling host IT under CI's `-T2`.
- `DomainMcpHostConfigLocationTest` — fast, no context, no containers: asserts every co-hosted module
  ships its own yml at `config/<module>/`, declaring its production port; that the default classpath
  location is kept; and that the config location is not `optional:`. This is the only guard on a coupling
  that lives in five separate poms.

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- `agent-host` + `platform-host` still boot on the old `spring.config.name` skip — the per-module config
  location has to be rolled out to them before their `main` can be deployable either, and they will meet
  the same classpath-merge question (the platform tier is DB-bound throughout, the agent tier is not).
