# agent-host

**Status (2026-09-09):** #584 slice 3d — the **resident Agent-hot** host; co-residency of the real hot
set proven by IT, not yet a deployable process. ADR-0006 Path B (process consolidation), approach B1.

Boots the always-on `Agent-hot` LLM agent contexts **in one JVM**, each an independent Spring context on
its own port — so every agent keeps its `/internal/*` and inbound endpoints on the same URL/port and no
contract changes. The win is that the ~300 MB per-process JVM baseline (JIT/code-cache, metaspace, GC,
thread pools) is paid **once per host** instead of once per module. Agents are LLM-bound and own no
schema, so this host is kept separate from the DB-bound `domain-mcp-host` (topology-map.md §Grouping 2).

The resident hot set ([plans/topology-map.md](../../plans/topology-map.md) §Resident):
`calendar-agent · finance-agent · tasks-agent · notes-agent · coordinator-agent · researcher-agent`.

Depends on each agent's **plain classes jar** (enabled by #584 slice 3a, the `-exec` classifier). Two
shared-classpath collisions are resolved:
- **`application.yml`** (all at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-agent `@ConfigurationProperties` self-default
  to the compose hostnames, and the launcher/deploy env supply the rest.
- **`AGENT.md`** (used to be `classpath:/AGENT.md` in every jar) — #584 3d moved each to a per-agent
  `manifest/<name>/AGENT.md` (module `pom.xml` `<targetPath>`, file stays at the module root), and the
  launcher points each context's `agent.manifest-classpath` there so the right persona loads.

## Key classes
- `AgentHost` — the launcher. `RESIDENT_HOT` lists the co-hosted agents (`Agent` record = app class +
  short name + skills glob); `structuralProps` supplies the config-name skip, reactive mode, and the
  per-agent manifest/skills paths; `boot(Agent, extra)` starts one context; `main` is the deploy entry
  point (env→per-context outbound wiring lands with the rollout, ADR-0006 item 4).

## Tests
- `AgentHostFootprintIntegrationTest` (`it`, no container) — boots all six agent contexts in one JVM
  (MCP client off, random ports) and asserts co-residency: all live, six distinct ports, each with only
  its own application bean **and its own AGENT.md persona** (proving the classpath collision fix).

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs six JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining hosts (cold host-units) — same mechanism, per-tier lists. Platform-hot shipped (#584 3e,
  `deploy/platform-host`), completing the resident tier.
- A deployable/runnable host artifact (packaging of a multi-context launcher).
