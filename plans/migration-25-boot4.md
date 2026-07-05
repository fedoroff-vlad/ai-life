# Platform migration — Java 25 + Spring Boot 4 + Spring AI 2

Authority file for the **platform version migration**. Owner decision (2026-07-03): **first finish the
ambient-capture / second-memory stage** ([ambient-capture.md](ambient-capture.md), AC-1..5 + anything else
tied to the "вторая память"), **then** do this migration as its own dedicated stage — not mixed with
feature work. Sequencing is locked in [architecture.md](architecture.md) §Locked decisions.

## Target versions (verified 2026-07)
| Component | From | To | Notes |
|---|---|---|---|
| Java | 21 LTS | **25 LTS** | Next LTS after 21 (Sep 2025). Not 26 (non-LTS). |
| Spring Boot | 3.4.x | **4.0.x+** (4.1 is out) | On Spring Framework 7. Baseline Java 17, first-class Java 25, Jakarta EE 11, Servlet 6.1. |
| Spring AI | 1.0.x | **2.0.x** | **Hard requirement:** Boot 4 needs Spring AI 2.0 (1.0 is Boot 3.x only). Spring AI 2.0 GA = 2026-05-28, requires Java 21+. Our central dep (MCP client). |

## Why (advantages that matter for us)
- **Java 25 — cheap, isolated win:**
  - *Compact Object Headers* (JEP 519, final in 25) → less heap per object. Directly eases the CI OOM risk we already flag (2-vCPU/7 GB runner + parallel Testcontainers, CLAUDE.md §Test strategy).
  - Faster startup / AOT profiling (Leyden bits), maturer generational ZGC/Shenandoah — helps ~10 small JVM services.
  - Finalized language ergonomics (flexible constructor bodies, module imports, scoped values) — minor for mature code.
- **Spring Boot 4 / Framework 7 — real but heavier:**
  - *JSpecify null-safety* annotations → compile-time null checks across a large codebase.
  - *Built-in resilience* (`@Retryable`, `@ConcurrencyLimit` in core) → replaces our hand-rolled best-effort/soft-fail on the many HTTP/MCP hops (ProfileClient, MemoryClient, `/internal/*` calls, llm-gateway).
  - *Declarative HTTP interface clients* → could simplify our WebClient-based inter-service clients.
  - API versioning, Jackson 3.

## Constraints / risks
- **Double major at once:** Boot 3.4→4.0 **and** Spring AI 1.0→2.0 land together; Spring AI 2.0 has its own breaking API changes on top of Boot 4's removed deprecations + config renames.
- **Spring AI 2.0 GA is fresh** (~since 2026-05-28) — let a few 2.0.x patches settle before committing.
- **Ecosystem tail:** MCP starter, Testcontainers, Liquibase, pgvector driver must all be on Boot-4-compatible releases. GraalVM native-image (if ever) needs v25+.

## Suggested approach (own branch, full CI, not mixed with features)
1. **Java 25 first, as an isolated slice** — bump `<java.version>` / `maven.compiler.release` to 25 in the root pom + CI JDK matrix; run full `mvn verify`. Independent of Boot/AI, ships on its own. Good first spike to see what (if anything) reddens.
2. **Then Boot 4 + Spring AI 2.0 as a dedicated migration stage** — follow the [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), bump Spring AI to 2.0.x, work through breaking changes module-by-module (GIB helps scope PR CI). Land between feature stages, not during one. Update `architecture.md` §Stack + module READMEs that name versions.
3. **Build/CI performance pass (dedicated slice, after the version bumps land green)** — see §Build/CI performance below. Do it *after* Java 25 + Boot 4 so the scan measures the new baseline, and the JVM/Boot wins (compact object headers, faster startup) are already in the numbers.

## Build/CI performance — the growth problem (owner flag, 2026-07-05)
The serial full `mvn verify` is now **~15 min** locally (and comparable on the runner). The repo is 51
modules and still growing, and roughly a dozen of them spin their own Testcontainers (PG / Radicale /
MinIO). This scales badly: if the per-stage build keeps creeping up, it becomes an unacceptable tax on
every PR and every stage closer. **After the migration lands, do a full build-performance scan and
propose concrete speedups** — treat build time as a first-class constraint, not an afterthought.

Where the time actually goes (measure first, don't guess): the dominant cost is almost certainly the
**Testcontainers-backed integration tests** (container start/stop × ~12 modules, serial on purpose to
avoid OOM on the 2-vCPU/7 GB runner — CLAUDE.md §Test strategy), not compilation. So the highest-leverage
levers are about *container reuse* and *parallelism the runner can survive*, which the Java 25 heap win
(compact object headers) may finally make safe:
- **Testcontainers singleton/reuse** — one shared PG (+ Radicale/MinIO) across the modules that only need
  a schema, via the reuse flag / a shared singleton container, instead of a fresh container per module.
  Biggest expected win; the CLAUDE.md note already points here as the first lever before touching `-T`.
- **Re-evaluate `-T` on `verify`** — parallelism on `verify` is disabled today purely because concurrent
  containers risk OOM on the small runner. Java 25's lower heap-per-object + a bigger runner (paid) may
  make `-T2C` safe. Re-measure OOM headroom on 25 before enabling.
- **CI: GIB is already on for PRs** (builds only changed modules + upstreams); confirm it's pruning as
  expected and that the docs-only skip still fires. The gap is the **main-branch** full run and local
  `verify` — those stay full by design, so the container-reuse lever matters most there.
- **Split slow ITs from fast unit tests** — a Maven profile / surefire-vs-failsafe split so the default
  loop runs only fast tests and the container ITs run in a separate (parallelisable) phase.
- **Cheaper knobs** — Maven build cache (skip unchanged module rebuilds), `-o`/offline where safe, JVM
  fork tuning for surefire, dependency-resolution caching on the runner.

Deliverable of the pass: a measured breakdown (which modules/phases dominate) + a ranked list of changes
with expected time saved, applied incrementally with the full `verify` staying green. Keep the serial
run boring and reliable — trade for speed only where the OOM headroom is proven on the new baseline.

## Sources
- [Spring Boot 4.0.0 available now](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/) · [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [Framework 7.0 GA](https://spring.io/blog/2025/11/13/spring-framework-7-0-general-availability/)
- [Spring AI 2.0 milestone blog](https://spring.io/blog/2026/03/26/spring-ai-2-0-0-M4-and-1-1-4-and-1-0-5-available/) · [Spring AI ↔ Boot 4 discussion #5149](https://github.com/spring-projects/spring-ai/discussions/5149)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) · [Spring Boot 4.1 (InfoQ)](https://www.infoq.com/news/2026/06/spring-boot-4-1/)
