# finance-aux-host

**Status (2026-09-15):** #584 slice 3j — the **fifth cold host-unit** (the **Finance-aux** unit); the first
**MCP-only** cold unit (no agent). Co-residency of four aux MCPs in one JVM proven by IT, not yet a
deployable process. ADR-0006 Path B (process consolidation), approach B1.

Boots the Finance-aux cluster's contexts — two schema-less capability-MCPs (`mcp-market-data` quotes,
`mcp-chart-render` PNG rendering) and two import domain-MCPs (`mcp-money-pro-import` Money Pro CSV history,
`mcp-ics-import` read-only ICS feeds) — **in one JVM**, each an independent Spring context on its own port,
so all keep their endpoints (`/internal/*` + MCP/SSE) on the same URLs/ports and no contract changes.

**Why cold hosts differ from the resident tier.** Resident hosts keep the agent tier and the MCP tier in
separate JVMs (topology-map.md §Grouping 2). Cold hosts instead group by **co-usage affinity** (§Grouping
1): a whole cold cluster is started/stopped as a unit. Finance-aux is the **first cold unit with no agent** —
these four are all seldom-used finance/calendar helpers that can wake together, reclaiming the ~300 MB
per-process JVM baseline for the whole cluster at once (**4 JVMs → 1**). Because there is no agent, it needs
neither the per-agent manifest-path fix (#584 3d) nor the `agent.skills-classpath` re-supply — like the
resident Domain-MCP-hot host (3c) it only has to skip the shared `application.yml` and pin the web type. The
only cold unit left after this is the parked **Coach** unit (#289).

Depends on each module's **plain classes jar** (enabled by #584 slice 3j, the `-exec` classifier). Two
shared-classpath concerns are handled by the launcher:
- **`application.yml`** (all at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-module `@ConfigurationProperties` self-default
  to the compose hostnames/ports.
- **web type** — the config-name skip drops each module's own `spring.main.web-application-type`, so the
  launcher re-supplies it. `mcp-market-data` and `mcp-chart-render` are webflux-only → reactive;
  `mcp-money-pro-import` and `mcp-ics-import` carry both `spring-web` and `webflux` (like `mcp-creator`), so
  they are pinned servlet rather than left to an ambiguous auto-detect.

## Key classes
- `FinanceAuxHost` — the launcher. `COLD_FINANCE_AUX` lists the unit's modules (`Hosted` record = app class +
  short name + web type; no skills-glob field, as there is no agent); `structuralProps` supplies the
  config-name skip and web type; `boot(Hosted, extra)` starts one context; `main` is the deploy entry point
  (env→per-context wiring lands with the rollout).

## Tests
- `FinanceAuxHostFootprintIntegrationTest` (`it`, Testcontainers PG) — boots all four Finance-aux contexts in
  one JVM (random ports, `ddl-auto=none`) and asserts co-residency: all live in one process, four distinct
  ports, each context carrying only its own application bean. The two DB-bound import MCPs boot against the
  shared PG with no schema (they hold no fatal startup query).

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs four JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining cold host-unit (Coach, parked #289) — same mechanism.
- A deployable/runnable host artifact + the on-demand start/stop lifecycle (LC-2).
