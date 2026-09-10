# docs-host

**Status (2026-09-10):** #584 slice 3f — the **first cold host-unit** (the **Docs** unit); co-residency
of an agent + its domain-MCP in one JVM proven by IT, not yet a deployable process. ADR-0006 Path B
(process consolidation), approach B1.

Boots the Docs domain's contexts — `docs-agent` (the LLM specialist) and `mcp-docs` (its domain-MCP) —
**in one JVM**, each an independent Spring context on its own port, so both keep their endpoints
(`/agents/docs/*`, `/internal/documents` + MCP/SSE) on the same URLs/ports and no contract changes.

**Why cold hosts differ from the resident tier.** Resident hosts keep the agent tier and the MCP tier in
separate JVMs (topology-map.md §Grouping 2). Cold hosts instead group by **co-usage affinity** (§Grouping
1): a whole cold cluster is started/stopped as a unit, so co-hosting the agent with its MCP reclaims the
~300 MB per-process JVM baseline for the entire unit at once. This is the pilot that proves an agent and a
domain-MCP can share one JVM; the other cold units (Content, Lifestyle, Brief+Travel, Finance-aux, Coach)
follow the same mechanism.

Depends on each module's **plain classes jar** (enabled by #584 slice 3f, the `-exec` classifier). Three
shared-classpath concerns are handled by the launcher:
- **`application.yml`** (both at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-module `@ConfigurationProperties` self-default
  to the compose hostnames/ports.
- **web type** — the config-name skip drops each module's own `spring.main.web-application-type`, so the
  launcher re-supplies it (`docs-agent` reactive; `mcp-docs` carries both `spring-web` and `webflux`, so
  it is pinned servlet rather than left to an ambiguous auto-detect).
- **`agent.skills-classpath`** — it also lives in the skipped yml, and the runtime **fails startup** if
  `AGENT.md` declares skills the (then empty) registry never loaded, so the launcher re-supplies the docs
  skills glob. Only `docs-agent` carries an `AGENT.md` (`mcp-docs` has none), so the resident tier's
  per-agent manifest-path fix (#584 3d) is **not** needed for a single-agent host — `classpath:/AGENT.md`
  is unambiguous.

## Key classes
- `DocsHost` — the launcher. `COLD_DOCS` lists the unit's modules (`Hosted` record = app class + short
  name + web type + optional skills glob); `structuralProps` supplies the config-name skip, web type, and
  (for the agent) the manifest + skills classpath; `boot(Hosted, extra)` starts one context; `main` is the
  deploy entry point (env→per-context wiring + the agent→MCP SSE binding land with the rollout).

## Tests
- `DocsHostFootprintIntegrationTest` (`it`, Testcontainers PG) — boots both Docs contexts in one JVM
  (random ports, `ddl-auto=none`) and asserts co-residency: both live in one process, two distinct ports,
  each context carrying only its own application bean. `docs-agent` boots with its MCP client disabled
  (the agent→MCP SSE binding is a deploy concern, not this proof).

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs two JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining cold host-units (Content, Lifestyle, Brief+Travel, Finance-aux, Coach) — same mechanism.
- A deployable/runnable host artifact + the on-demand start/stop lifecycle (LC-2) + the agent→co-hosted-MCP
  SSE binding.
