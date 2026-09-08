# domain-mcp-host

**Status (2026-09-08):** #584 slice 3b — **mechanism proven**, not yet a deployable process.
ADR-0006 Path B (process consolidation), approach B1.

Boots several `domain-MCP` module contexts **in one JVM**, each an independent Spring context on its
own port with its own MCP server — so agents keep calling each MCP's `/internal/*` on the same URL/port
and every contract is unchanged. The win is that the ~300 MB per-process JVM baseline (JIT/code-cache,
metaspace, GC, thread pools) is paid **once per host** instead of once per module.

Depends on the pilot MCP modules' **plain classes jars** (enabled by #584 slice 3a, which moved each
module's executable to the `-exec` classifier). Each context is booted with a unique, non-existent
`spring.config.name` so the modules' `application.yml` resources (all at `classpath:/application.yml`)
do not collide; every value is supplied by the caller / deploy environment.

## Key classes
- `DomainMcpHost` — the launcher. `PILOT` lists the co-hosted modules (`mcp-briefing`, `mcp-travel`);
  `boot(Hosted, props)` starts one context; `main` is the deploy entry point (env→per-context wiring
  lands with the rollout, ADR-0006 item 4).

## Tests
- `DomainMcpHostFootprintIntegrationTest` (`it`) — boots both pilot contexts in one JVM against a
  Testcontainers PG and asserts co-residency: both live, distinct ports, each with only its own beans.

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs two JVMs) — needs the env-wired `main` + `measure-footprint.sh`.
- Rollout across the rest of the MCP / agent / platform tiers (ADR-0006 item 4).
- A deployable/runnable host artifact (packaging of a multi-context launcher).
