# content-host

**Status (2026-09-11):** #584 slice 3g — the **second cold host-unit** (the **Content** unit); co-residency
of an agent + its domain-MCP + three trend capability-MCPs in one JVM proven by IT, not yet a deployable
process. ADR-0006 Path B (process consolidation), approach B1.

Boots the Content cluster's contexts — `creator-agent` (the LLM specialist), its domain-MCP `mcp-creator`,
and the three trend capability-MCPs it binds (`mcp-youtube`, `mcp-reddit`, `mcp-feeds`) — **in one JVM**,
each an independent Spring context on its own port, so all keep their endpoints (`/agents/creator/*`,
`/internal/*` + MCP/SSE) on the same URLs/ports and no contract changes.

**Why cold hosts differ from the resident tier.** Resident hosts keep the agent tier and the MCP tier in
separate JVMs (topology-map.md §Grouping 2). Cold hosts instead group by **co-usage affinity** (§Grouping
1): a whole cold cluster is started/stopped as a unit, so co-hosting the agent with the MCPs it fans out to
reclaims the ~300 MB per-process JVM baseline for the entire unit at once (here **5 JVMs → 1**). This is the
same agent+MCP co-hosting the Docs unit (3f) proved, now over a larger cluster (one agent, four MCPs); the
remaining cold units (Lifestyle, Brief+Travel, Finance-aux, Coach) follow the same mechanism.

Depends on each module's **plain classes jar** (enabled by #584 slice 3g, the `-exec` classifier). Three
shared-classpath concerns are handled by the launcher:
- **`application.yml`** (all at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-module `@ConfigurationProperties` self-default
  to the compose hostnames/ports.
- **web type** — the config-name skip drops each module's own `spring.main.web-application-type`, so the
  launcher re-supplies it. `creator-agent` and the three capability-MCPs are webflux-only → reactive;
  `mcp-creator` carries both `spring-web` and `webflux` (like `mcp-docs`), so it is pinned servlet rather
  than left to an ambiguous auto-detect.
- **`agent.skills-classpath`** — it also lives in the skipped yml, and the runtime **fails startup** if
  `AGENT.md` declares skills the (then empty) registry never loaded, so the launcher re-supplies the creator
  skills glob. Only `creator-agent` carries an `AGENT.md` (the four MCPs have none), so the resident tier's
  per-agent manifest-path fix (#584 3d) is **not** needed for a single-agent host — `classpath:/AGENT.md` is
  unambiguous.

## Key classes
- `ContentHost` — the launcher. `COLD_CONTENT` lists the unit's modules (`Hosted` record = app class + short
  name + web type + optional skills glob); `structuralProps` supplies the config-name skip, web type, and
  (for the agent) the manifest + skills classpath; `boot(Hosted, extra)` starts one context; `main` is the
  deploy entry point (env→per-context wiring + the agent→MCP SSE bindings land with the rollout).

## Tests
- `ContentHostFootprintIntegrationTest` (`it`, Testcontainers PG) — boots all five Content contexts in one
  JVM (random ports, `ddl-auto=none`) and asserts co-residency: all live in one process, five distinct
  ports, each context carrying only its own application bean. `creator-agent` boots with its MCP client
  disabled (the agent→MCP SSE binding is a deploy concern, not this proof).

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs five JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining cold host-units (Lifestyle, Brief+Travel, Finance-aux, Coach) — same mechanism.
- A deployable/runnable host artifact + the on-demand start/stop lifecycle (LC-2) + the agent→co-hosted-MCP
  SSE bindings.
