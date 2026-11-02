# platform-host

**Status (2026-11-02):** #584 slice 3e (co-residency) + **3s (per-module config)** — the **resident
Platform-hot** host; co-residency of the real hot set proven by IT, not yet a deployable process.
ADR-0006 Path B (process consolidation), approach B1.

Boots the always-on `Platform-hot` service contexts **in one JVM**, each an independent Spring context on
its own port — so every service keeps its `/v1/*`, `/internal/*` and inbound endpoints on the same
URL/port and no contract changes. The win is that the ~300 MB per-process JVM baseline (JIT/code-cache,
metaspace, GC, thread pools) is paid **once per host** instead of once per module.

The resident hot set ([plans/topology-map.md](../../plans/topology-map.md) §Resident):
`gateway-telegram · orchestrator · profile-service · notifier-service · scheduler-service ·
conversation-service · media-service`. The two isolated singletons — **memory-service** (heavy pgvector +
Apache AGE on the recall hot path) and **llm-gateway** (holds the model + its own LC-4 downshift
lifecycle) — are deliberately **not** consolidated (topology-map.md §Grouping 3).

Depends on each service's **plain classes jar** (enabled by #584 slice 3e, the `-exec` classifier). Three
shared-classpath concerns are handled by the launcher:

- **`application.yml`** (all at `classpath:/application.yml`) — each service's pom lands a **copy** of its
  own yml at `config/<name>/`, and each context is booted with
  `spring.config.location=classpath:/config/<name>/`. One source file, two classpath locations: nothing to
  keep in sync by hand, and the standalone `-exec.jar` still resolves `classpath:/application.yml`
  unchanged. This replaced the old unique-but-non-existent `spring.config.name` skip (slice 3s), which had
  made each context ignore *all* of its own configuration — tolerable for an IT that passes properties
  explicitly, impossible for a deployable process, because on this tier the yml **is** the `env → property`
  mapping: each service's port, its `*_DB_URL`, the outbound `PROFILE_SERVICE_URL` / `ORCHESTRATOR_URL`
  hostnames, the inbox and scheduler tuning, the S3 bucket.
  The location is deliberately **not** `optional:` — a context that fell back to a sibling's configuration
  would bind someone else's port and dial someone else's dependencies.
- **web type** — the launcher re-supplies `spring.main.web-application-type` per member. Unlike the
  Agent-hot host (slice 3q, where this re-supply could be deleted because every agent's yml declares it),
  here only **two of seven** ymls do — `gateway-telegram` (reactive) and `scheduler-service` (servlet) — so
  for the other five the launcher is the *only* source, not a duplicate. It is load-bearing: four members
  bring `spring-boot-starter-web`, so on a merged classpath Boot deduces SERVLET for all seven, and the
  three reactive ones would mount their routes nowhere while `/actuator/health` stayed green — the exact
  way the Domain-MCP-hot host shipped a dead MCP surface in slice 3p.
- **persistence** — `orchestrator` is the one **schema-less** member: the only service whose yml carries no
  `spring.datasource` (it is a pure HTTP router). Co-hosted it inherits its six siblings' JPA/JDBC
  starters, so without excludes Boot auto-configures a `DataSource` it has no URL for and the context dies
  on *"Failed to determine a suitable driver class"* (the slice 3o failure). `Persistence.SCHEMA_LESS`
  keeps that auto-configuration off.
  No MCP transport split is needed here (unlike `domain-mcp-host`): no platform module is an MCP server,
  so neither transport starter reaches this classpath. A future member that is one would bring that
  collision back.

## Key classes
- `PlatformHost` — the launcher. `RESIDENT_HOT` lists the co-hosted services (`Hosted` record = app class
  + module name + `Persistence` + web type); `configLocation()` points at the per-module yml;
  `excludedAutoConfigurations()` is the per-member exclusion set; `boot(Hosted, overrides)` starts one
  context; `main` is the deploy entry point. `boot` and `main` share one `launchArgs` on purpose — on the
  Domain-MCP-hot host two separate wiring paths let the IT pass while the deploy died (slice 3o).
  Overrides are passed as **command-line arguments**, not builder properties: builder properties land in
  `defaultProperties`, which ranks *below* config files, so they would lose to the yml.

## Tests
- `PlatformHostConfigLocationTest` (fast — no Spring context, no containers) — guards the coupling that
  lives in seven separate poms and the two launcher flags that are claims about files elsewhere: the
  per-module copy exists and is that service's own, it declares its production port, `Persistence` is
  **derived from the yml** (schema-bound exactly when a datasource is declared), and `webType` **agrees
  with every yml that declares one** (the launcher's command-line argument outranks the yml, so a
  disagreement would run a service on a stack its own configuration contradicts).
  ⚠ Proving this guard fails needs **`mvn clean`**: Maven leaves an already-copied resource in
  `target/classes`, so a local green right after removing a pom block means nothing (the slice 3q trap).
- `PlatformHostFootprintIntegrationTest` (`it`, Testcontainers PG + SeaweedFS) — boots all seven platform
  contexts in one JVM (random ports) and asserts co-residency: all live, seven distinct ports, each
  context carrying only its own application bean, **reading its own yml**, on the web stack its entry
  declares (the context *type*, not just that something answers — 3r's lesson), answering on its own port,
  and **no `DataSource` bean for `orchestrator`**. Only schema-bound members are handed a datasource;
  giving one to all seven is what hid the 3o failure behind a green IT. media-service gets a live SeaweedFS
  container (its `@PostConstruct` ensures a bucket); the two `@Scheduled` ticks are kept quiet
  (`notifier.held-redrain-enabled=false`, far-future `scheduler.tick-millis`); gateway boots on the empty
  bot token its own yml defaults to, so the bot + inbox redriver stay off.

## Not yet done (see [plans/topology-map.md](../../plans/topology-map.md) §Slice 3)
- A **deployable artifact + the compose cutover** for this tier (Boot `repackage` with an explicit
  `mainClass`, a Dockerfile, network aliases for the seven service names) and the **RAM delta**
  measurement (one host vs seven JVMs) via `measure-footprint.sh` on the running stack (Mac).
  This is the riskiest cutover of the three: `gateway-telegram` holds the Telegram connection and
  `scheduler-service` the cron, and one restart takes all seven down.
- The remaining hosts (cold host-units) — same mechanism, per-tier lists.
