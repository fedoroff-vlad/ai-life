# brief-travel-host

**Status (2026-09-12):** #584 slice 3i — the **fourth cold host-unit** (the **Brief+Travel** unit), a
multi-agent cold unit; co-residency of two agents + their domain-MCPs + two capability-MCPs in one JVM
proven by IT, not yet a deployable process. ADR-0006 Path B (process consolidation), approach B1.

Boots the Brief+Travel cluster's contexts — `briefing-agent`, `travel-agent` (the LLM specialists), their
domain-MCPs `mcp-briefing` + `mcp-travel`, and the two capability-MCPs they bind (`mcp-weather`,
`mcp-travel-search`) — **in one JVM**, each an independent Spring context on its own port, so all keep their
endpoints (`/agents/*`, `/internal/*` + MCP/SSE) on the same URLs/ports and no contract changes.

**Why cold hosts differ from the resident tier.** Resident hosts keep the agent tier and the MCP tier in
separate JVMs (topology-map.md §Grouping 2). Cold hosts instead group by **co-usage affinity** (§Grouping
1): a whole cold cluster is started/stopped as a unit, so co-hosting the agents with the MCPs they fan out
to reclaims the ~300 MB per-process JVM baseline for the entire unit at once (here **6 JVMs → 1**).

**Multi-agent unit** (like Lifestyle, 3h). Two agent contexts share the host's classloader, so — exactly as
the resident Agent-hot host (#584 3d) — each `AGENT.md` is landed at a per-agent `manifest/<name>/AGENT.md`
(module `pom.xml` `<targetPath>`; the file stays at the module root) and the launcher points each context's
`agent.manifest-classpath` there so it resolves its own persona.

Depends on each module's **plain classes jar** (the `-exec` classifier — `mcp-briefing` + `mcp-travel` got
it in the #584 3a pilot, the rest in 3i). Two more shared-classpath concerns are handled by the launcher:
- **`application.yml`** (all at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-module `@ConfigurationProperties` self-default to
  the compose hostnames/ports.
- **web type** — the config-name skip drops each module's own `spring.main.web-application-type`, so the
  launcher re-supplies it. The two agents + the two capability-MCPs are webflux-only → reactive;
  `mcp-briefing` + `mcp-travel` carry both `spring-web` and `webflux` (like `mcp-docs`/`mcp-creator`), so
  they are pinned servlet rather than left to an ambiguous auto-detect.

Each agent also needs its `agent.skills-classpath` re-supplied (it lives in the skipped yml, and the runtime
**fails startup** if `AGENT.md` declares skills the empty registry never loaded).

## Key classes
- `BriefTravelHost` — the launcher. `COLD_BRIEF_TRAVEL` lists the unit's modules (`Hosted` record = app
  class + short name + web type + optional skills glob; `isAgent()` = has a skills glob); `structuralProps`
  supplies the config-name skip, web type, and (for an agent) the per-agent manifest path + skills classpath;
  `boot(Hosted, extra)` starts one context; `main` is the deploy entry point (env→per-context wiring + the
  agent→MCP SSE bindings land with the rollout).

## Tests
- `BriefTravelHostFootprintIntegrationTest` (`it`, Testcontainers PG) — boots all six Brief+Travel contexts
  in one JVM (random ports, `ddl-auto=none`) and asserts co-residency: all live in one process, six distinct
  ports, each context carrying only its own application bean, **and each of the two agents loaded its own
  `AGENT.md` persona** (the per-agent manifest-path fix). Agents boot with their MCP client disabled (the
  agent→MCP SSE binding is a deploy concern, not this proof).

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs six JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining cold host-units (Finance-aux, Coach) — same mechanism.
- A deployable/runnable host artifact + the on-demand start/stop lifecycle (LC-2) + the agent→co-hosted-MCP
  SSE bindings.
