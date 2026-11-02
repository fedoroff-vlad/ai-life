# domain-mcp-host

**Status (2026-11-02):** #584 slice 3n — the **resident Domain-MCP-hot** host. Co-residency of the real
hot set is proven by IT and each context now boots on **its own configuration**; the deployable artifact
(Dockerfile + compose + the RAM delta) is the next slice. ADR-0006 Path B (process consolidation),
approach B1.

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

## Key classes
- `DomainMcpHost` — the launcher. `RESIDENT_HOT` lists the co-hosted modules; `Hosted.configLocation()`
  is the module's own config path; `boot(Hosted, overrides)` starts one context, passing `overrides` as
  **command-line args** (Boot's builder properties rank *below* config files, so a caller's value would
  otherwise lose to the yml); `main` is the deploy entry point — it boots every resident context on its
  own yml, so the standalone services' env vars keep working untouched. Cold hosts (Content, Lifestyle,
  Brief+Travel, …) each get their own launcher list.

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
- A deployable/runnable host artifact — the **Dockerfile + compose service** that replaces the five
  separate ones (its image must carry what the members need: `ffmpeg` for `mcp-media-processing`, and
  `yt-dlp` once slice 3l folds `mcp-media-fetch` in).
- The **RAM delta** measurement (one host vs five JVMs) on the running stack, now that
  `scripts/measure-footprint.sh` runs on the Mac. Baseline for these five: **2954 MB** across five JVMs
  (2026-11-02).
- `agent-host` + `platform-host` still boot on the old `spring.config.name` skip — the per-module config
  location has to be rolled out to them before their `main` can be deployable either.
