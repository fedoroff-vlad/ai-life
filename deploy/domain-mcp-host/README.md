# domain-mcp-host

**Status (2026-09-08):** #584 slice 3c — the **resident Domain-MCP-hot** host; co-residency of the real
hot set proven by IT, not yet a deployable process. ADR-0006 Path B (process consolidation), approach B1.

Boots the always-on `Domain-MCP-hot` module contexts **in one JVM**, each an independent Spring context
on its own port with its own MCP server — so agents keep calling each MCP's `/internal/*` on the same
URL/port and every contract is unchanged. The win is that the ~300 MB per-process JVM baseline
(JIT/code-cache, metaspace, GC, thread pools) is paid **once per host** instead of once per module.

The resident hot set ([plans/topology-map.md](../../plans/topology-map.md) §Resident):
`mcp-caldav · mcp-finance · mcp-tasks · mcp-web · mcp-media-processing`.

Depends on each module's **plain classes jar** (enabled by #584 slice 3a, which moved every co-hosted
module's executable to the `-exec` classifier). Each context is booted with a unique, non-existent
`spring.config.name` so the modules' `application.yml` resources (all at `classpath:/application.yml`)
do not collide; every value is supplied by the caller / deploy environment. All five modules'
`@ConfigurationProperties` self-default in Java, so a config-name-skipped context still starts.

## Key classes
- `DomainMcpHost` — the launcher. `RESIDENT_HOT` lists the co-hosted modules; `boot(Hosted, props)`
  starts one context; `main` is the deploy entry point (env→per-context wiring lands with the rollout,
  ADR-0006 item 4). Cold hosts (Content, Lifestyle, Brief+Travel, …) each get their own launcher list.

## Tests
- `DomainMcpHostFootprintIntegrationTest` (`it`) — boots all five resident contexts in one JVM against a
  Testcontainers PG and asserts co-residency: all live, five distinct ports, each with only its own
  application bean.

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs five JVMs) — needs the env-wired `main` + `measure-footprint.sh`
  on the running stack (Mac; the dev VDI has no Docker daemon).
- The remaining hosts (cold host-units) — same mechanism, per-tier lists. Agent-hot (#584 3d) and
  Platform-hot (#584 3e) shipped, completing the resident tier.
- A deployable/runnable host artifact (packaging of a multi-context launcher).
