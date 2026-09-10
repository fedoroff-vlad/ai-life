# platform-host

**Status (2026-09-10):** #584 slice 3e — the **resident Platform-hot** host; co-residency of the real
hot set proven by IT, not yet a deployable process. ADR-0006 Path B (process consolidation), approach B1.

Boots the always-on `Platform-hot` service contexts **in one JVM**, each an independent Spring context on
its own port — so every service keeps its `/v1/*`, `/internal/*` and inbound endpoints on the same
URL/port and no contract changes. The win is that the ~300 MB per-process JVM baseline (JIT/code-cache,
metaspace, GC, thread pools) is paid **once per host** instead of once per module.

The resident hot set ([plans/topology-map.md](../../plans/topology-map.md) §Resident):
`gateway-telegram · orchestrator · profile-service · notifier-service · scheduler-service ·
conversation-service · media-service`. The two isolated singletons — **memory-service** (heavy pgvector +
Apache AGE on the recall hot path) and **llm-gateway** (holds the model + its own LC-4 downshift
lifecycle) — are deliberately **not** consolidated (topology-map.md §Grouping 3).

Depends on each service's **plain classes jar** (enabled by #584 slice 3e, the `-exec` classifier). Two
shared-classpath concerns are handled by the launcher:
- **`application.yml`** (all at `classpath:/application.yml`) — each context is booted with a unique,
  non-existent `spring.config.name` that skips it; the per-module `@ConfigurationProperties` self-default
  to the compose hostnames/ports, and the launcher/deploy env supply the rest.
- **web type** — because the config-name skip also drops each module's own
  `spring.main.web-application-type` declaration, the launcher **re-supplies it per module** (reactive:
  gateway/orchestrator/notifier; servlet: profile/conversation/media/scheduler). This is load-bearing for
  the platform tier: `scheduler-service` carries both `spring-web` and (test-only) `webflux`, so
  auto-detection would be ambiguous.

## Key classes
- `PlatformHost` — the launcher. `RESIDENT_HOT` lists the co-hosted services (`Hosted` record = app class
  + short name + web type); `structuralProps` supplies the config-name skip + web type; `boot(Hosted,
  extra)` starts one context; `main` is the deploy entry point (env→per-context outbound wiring lands with
  the rollout, ADR-0006 item 4).

## Tests
- `PlatformHostFootprintIntegrationTest` (`it`, Testcontainers PG + MinIO) — boots all seven platform
  contexts in one JVM (random ports, `ddl-auto=none`) and asserts co-residency: all live, seven distinct
  ports, each context carrying only its own application bean. media-service gets a live `MinIOContainer`
  (its `@PostConstruct` ensures a bucket); the two `@Scheduled` ticks are kept quiet
  (`notifier.held-redrain-enabled=false`, far-future `scheduler.tick-millis`); gateway boots token-less so
  the bot + inbox redriver stay off.

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- The **RAM delta** measurement (one host vs seven JVMs) — needs the env-wired `main` +
  `measure-footprint.sh` on the running stack (Mac).
- The remaining hosts (cold host-units) — same mechanism, per-tier lists.
- A deployable/runnable host artifact (packaging of a multi-context launcher).
