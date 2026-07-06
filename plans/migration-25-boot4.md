# Platform migration — Java 25 + Spring Boot 4 + Spring AI 2

Authority file for the **platform version migration**. Owner decision (2026-07-03): **first finish the
ambient-capture / second-memory stage** ([ambient-capture.md](ambient-capture.md), AC-1..5 + anything else
tied to the "вторая память"), **then** do this migration as its own dedicated stage — not mixed with
feature work. Sequencing is locked in [architecture.md](architecture.md) §Locked decisions.

## ✅ Status: DONE (2026-07-05) — Java 25 + Boot 4.0.7 + Spring AI 2.0.0 + Jackson 3

Landed as one coordinated bump (Java 25 could **not** be isolated — Spring Boot 3.4's `repackage`
tooling can't read Java 25 class files, so it went together with Boot 4). Full local `mvn verify` green:
**51/51 modules, ~1192 tests, all Testcontainers ITs**. Owner accepted Spring AI 2.0.0 GA despite its
freshness (only 2.0.x line at the time). What actually broke — all mechanical, **zero main-logic changes
beyond the Jackson package rename** — captured here so the next major bump is cheaper:

- **Versions:** root pom `maven.compiler.release=25`, `spring-boot.version=4.0.7`, `spring-ai.version=2.0.0`;
  dropped the dead `jackson.version` pin; CI `setup-java` 21 → 25.
- **Boot 4 test-slice / client relocations** (each now a separate module — declared **per-module**, never
  in the parent, to keep modules independent):
  - `@AutoConfigureWebTestClient` → package `org.springframework.boot.webtestclient.autoconfigure`, artifact
    **`spring-boot-webtestclient`** (test). Boot 4 also **no longer** provides `WebTestClient` implicitly for
    `@SpringBootTest(RANDOM_PORT)` — every such test needs the explicit `@AutoConfigureWebTestClient`.
  - `RestTemplateBuilder` → `org.springframework.boot.restclient` (**`spring-boot-restclient`**);
    `TestRestTemplate` → `org.springframework.boot.resttestclient` (**`spring-boot-resttestclient`**, whose
    auto-config also needs `spring-boot-restclient`) + the `@AutoConfigureTestRestTemplate` annotation.
  - `@MockBean`/`@SpyBean` **removed** → `@MockitoBean`/`@MockitoSpyBean`
    (`org.springframework.test.context.bean.override.mockito`).
  - **`WebClient.Builder` is no longer auto-configured** by `spring-boot-starter-webflux` (moved to
    **`spring-boot-webclient`**, Boot issue #48293) — added per-module to every service that injects it.
- **Jackson 2 → 3** (Boot 4 / Framework 7 default; the reactive HTTP codecs are Jackson 3, so Jackson-2
  types at the wire boundary fail at runtime even though they compile):
  - Package rename `com.fasterxml.jackson.databind` → `tools.jackson.databind` (+ non-annotation
    `…core` → `tools.jackson.core`) across 259 files; **annotations stay** `com.fasterxml.jackson.annotation`.
    contracts pom → `tools.jackson.core:jackson-databind`; dropped `jackson-datatype-jsr310` (java.time is
    built into Jackson 3).
  - API deltas: `JsonProcessingException` → unchecked `JacksonException`; `JsonNode.fields()` →
    `properties()`; `node.TextNode` → `StringNode`; `ObjectMapper.findAndRegisterModules()` removed
    (auto-registered); **`readTree` now fails on trailing tokens** — LLM-output parsers must slice `{`…`}`
    (fixed the 4 memory-service capture extractors).
  - **Hibernate 7.2 JSON columns:** its built-in format mapper is Jackson-2-only, so `@JdbcTypeCode(JSON)`
    fails with *"Could not find a FormatMapper for the JSON format"*. Added a Jackson-3
    [`Jackson3JsonFormatMapper`](../libs/platform-common/src/main/java/dev/fedorov/ailife/common/jackson/Jackson3JsonFormatMapper.java)
    in `platform-common`, wired per JPA service via `spring.jpa.properties.hibernate.type.json_format_mapper`.
- **Not needed:** the ASM-override bridge (only relevant while Java 25 sat on Boot 3.4) was removed once Boot 4
  landed. **Still open:** the build/CI performance pass below (full `verify` ≈ 11–12 min on Java 25).

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

## Build/CI performance — MEASURED (2026-07-06, Java-25 baseline)
The scan is done. **The measurement overturns the prior guess** ("Testcontainers container churn
dominates"). Machine: i5-12450H, 8c/12t, 15.7 GB RAM, Docker capped 7.8 GB.

### What the numbers say
| Run | Wall | Result |
|---|---|---|
| serial `mvn -B -ntp clean verify` (= CI main) | **11:13** | ✅ green |
| `-T4 clean verify`, testcontainers **reuse ON** | ~3:53 → **FAIL** | ❌ shared-DB collision |
| `-T4 clean verify`, reuse **OFF** (isolated PG/module) | **5:24** | ✅ 1192/1192 |

- **The cost is FLAT across 51 modules, not concentrated in container modules.** Top: finance-agent
  45.5s, memory-service 38.5s, nutritionist-agent 27.8s, tasks-agent 25.4s, scheduler 24.7s — then a
  long tail of ~40 modules at 8–25s. The top module is only ~7% of total. finance-agent (45s, **no PG**,
  MockWebServer) is *heavier* than memory-service (38s, ~10 Testcontainers ITs).
- => the bottleneck is **per-module JVM + Spring-context startup × 51 modules, run serially** — *not*
  Testcontainers churn. `libs` (no context) are <6s; compilation is cheap. A pgvector container starts in
  ~1.4s and reuse already amortises it on CI, so containers are not where the 11 min goes.

### The two headline levers are in TENSION (measured, not assumed)
- **Testcontainers singleton/reuse** (the plan's "biggest win") is **already built and shipped**:
  `libs/test-support/AbstractPostgresIntegrationTest` (`withReuse(true)`, ~30 IT classes) + CI sets
  `testcontainers.reuse.enable=true` (ci.yml). It helps the *serial* build (one shared PG).
- **`-T` module parallelism** is the real remaining lever (serial execution is the bottleneck), and it
  **cut wall time 11:13 → 5:24 (≈2.1×) with all 1192 tests green** — BUT only with **reuse OFF**. With
  reuse ON, concurrent modules share the one container and corrupt each other's schema/data
  (`bus.outbox` etc. — reproduced: mcp-nutrition `BasketCapturedConsumerIntegrationTest` saw `PENDING`
  vs `PUBLISHED`). Serial passed 100%, so it is purely the reuse×parallel interaction, not a product bug.
- **Resolution:** `-T` requires container **isolation** (reuse off, one PG per module). Isolated PG
  containers are ~150 MB each, so even `-T4` peaks at <1 GB of containers — the old OOM fear assumed
  heavy containers; the dominant memory is the parallel *JVM forks*, which 15.7 GB (local) / 7 GB (CI at
  `-T2`) both survive. So the migration's Java-25 heap win matters less than expected here; container
  memory was never the wall.

### Ranked plan (apply incrementally, full `verify` stays green)
1. **Enable `-T` parallelism with container isolation — the ≈2× win.**
   - **Local dev loop:** uncontroversial — document `mvn -T4 verify` (reuse off) in CLAUDE.md/README. Do now.
   - **CI (`main` full run):** flip serial → `-T2` **and remove** the `testcontainers.reuse.enable=true`
     line (the two are incompatible — see above). This reverses CLAUDE.md's deliberate "serial on purpose"
     policy, so it is an **owner decision**; `-T2` fits the current 2-vCPU/7 GB runner (2 isolated PG ≈
     300 MB), a paid bigger runner would allow `-T4`. Measure `-T2` OOM headroom on the runner before flipping.
2. **fast/slow test split (surefire unit vs failsafe IT).** Today *all* ITs (`*IntegrationTest`) run under
   surefire in the `test` phase; failsafe sits unused in `pluginManagement`. A split lets the fast dev
   loop skip container ITs entirely (big for iteration) — marginal for the *full* `verify` total. Medium.
3. **Build hygiene surfaced by the scan (zero perf, removes migration cruft — do first, it's free):**
   - Duplicate `spring-boot-webtestclient` dependency in **14 module poms** → 14 build warnings
     ("duplicate declaration … future Maven versions might no longer support building such malformed
     projects"). Dedupe.
   - **Dead pin:** `testcontainers.version=1.20.4` + its `testcontainers-bom` import are **overridden** by
     `spring-boot-dependencies` (imported first) → the effective TC version is Boot-4's **2.0.5** at
     runtime. Drop the pin, exactly like the migration dropped the dead `jackson.version` pin.
4. **Cheaper knobs (only if 1–2 fall short):** Maven build cache (skip unchanged modules), surefire fork
   tuning, dependency-resolution caching on the runner. GIB already prunes PR builds correctly (verified).

## Sources
- [Spring Boot 4.0.0 available now](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/) · [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [Framework 7.0 GA](https://spring.io/blog/2025/11/13/spring-framework-7-0-general-availability/)
- [Spring AI 2.0 milestone blog](https://spring.io/blog/2026/03/26/spring-ai-2-0-0-M4-and-1-1-4-and-1-0-5-available/) · [Spring AI ↔ Boot 4 discussion #5149](https://github.com/spring-projects/spring-ai/discussions/5149)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) · [Spring Boot 4.1 (InfoQ)](https://www.infoq.com/news/2026/06/spring-boot-4-1/)
