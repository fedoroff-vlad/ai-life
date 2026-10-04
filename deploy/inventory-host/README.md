# inventory-host

**Status (2026-10-04):** #584 slice 3k — the **sixth cold host-unit** (the **Inventory** unit);
co-residency of an agent + its domain-MCP in one JVM proven by IT, not yet a deployable process. ADR-0006
Path B (process consolidation), approach B1.

Boots the Inventory domain's contexts — `inventory-agent` (photographs things into containers, renders and
decodes their QR labels) and `mcp-inventory` (its domain-MCP over the `inventory.*` schema) — **in one
JVM**, each an independent Spring context on its own port, so both keep their endpoints
(`/agents/inventory/*`, `/internal/*` + MCP/SSE) on the same URLs/ports and no contract changes.

**Why this unit landed last.** The Inventory domain closed 2026-09-27, after the topology map was drawn
(2026-08-28), so it was the one domain with no host assignment at all. It takes the **Docs shape** (3f)
exactly: one agent plus its own domain-MCP, which also binds the *hot* `mcp-media-processing` (the vision
caption) without co-hosting it.

**Why cold hosts differ from the resident tier.** Resident hosts keep the agent tier and the MCP tier in
separate JVMs (topology-map.md §Grouping 2). Cold hosts instead group by **co-usage affinity** (§Grouping
1): "где что лежит" is an occasional ask, so the whole unit is started/stopped together and co-hosting the
agent with its MCP reclaims the ~300 MB per-process JVM baseline for the unit at once.

Depends on each module's **plain classes jar** (the `-exec` classifier, #584 slice 3a — both Inventory
modules were built after that enabler, so they already carried it). Three shared-classpath concerns are
handled by the launcher:
- **`application.yml`** (both at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-module `@ConfigurationProperties` self-default
  to the compose hostnames/ports.
- **web type** — the config-name skip drops each module's own `spring.main.web-application-type`, so the
  launcher re-supplies it (`inventory-agent` reactive; `mcp-inventory` carries both `spring-web` and
  `webflux`, so it is pinned servlet rather than left to an ambiguous auto-detect).
- **`agent.skills-classpath`** — it also lives in the skipped yml, and the runtime **fails startup** if
  `AGENT.md` declares skills the (then empty) registry never loaded, so the launcher re-supplies the
  inventory skills glob. Only `inventory-agent` carries an `AGENT.md` (`mcp-inventory` has none), so the
  resident tier's per-agent manifest-path fix (#584 3d) is **not** needed for a single-agent host —
  `classpath:/AGENT.md` is unambiguous.

## Key classes
- `InventoryHost` — the launcher. `COLD_INVENTORY` lists the unit's modules (`Hosted` record = app class +
  short name + web type + optional skills glob); `structuralProps` supplies the config-name skip, web type,
  and (for the agent) the manifest + skills classpath; `boot(Hosted, extra)` starts one context; `main` is
  the deploy entry point (env→per-context wiring + the agent→MCP SSE binding land with the rollout).

## Tests
- `InventoryHostFootprintIntegrationTest` (`it`, Testcontainers PG) — boots both Inventory contexts in one
  JVM (random ports, `ddl-auto=none`) and asserts co-residency: both live in one process, two distinct
  ports, each context carrying only its own application bean. `inventory-agent` boots with its MCP client
  disabled (the agent→MCP SSE binding is a deploy concern, not this proof).

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs two JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining cold host-unit (Coach, parked #289) — same mechanism.
- `mcp-media-fetch`'s placement into the resident **Domain-MCP-hot** host (slice 3l) — the last unplaced
  module; its host image must then carry `yt-dlp` + `ffmpeg`.
- A deployable/runnable host artifact + the on-demand start/stop lifecycle (LC-2) + the agent→co-hosted-MCP
  SSE binding.
