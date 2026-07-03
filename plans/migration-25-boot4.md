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

## Sources
- [Spring Boot 4.0.0 available now](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/) · [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [Framework 7.0 GA](https://spring.io/blog/2025/11/13/spring-framework-7-0-general-availability/)
- [Spring AI 2.0 milestone blog](https://spring.io/blog/2026/03/26/spring-ai-2-0-0-M4-and-1-1-4-and-1-0-5-available/) · [Spring AI ↔ Boot 4 discussion #5149](https://github.com/spring-projects/spring-ai/discussions/5149)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) · [Spring Boot 4.1 (InfoQ)](https://www.infoq.com/news/2026/06/spring-boot-4-1/)
