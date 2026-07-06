# test-support

**Status:** Active (Stage 7+, introduced #202)

Shared test utilities for integration tests across all modules.

## Purpose

`AbstractPostgresIntegrationTest` — base class that starts one `pgvector/pgvector:pg16`
Testcontainers container **per module JVM**. All subclasses in a module share that module's
container; **different modules get their own isolated containers** (reuse is off). That isolation
is deliberate — the build runs modules in parallel (`mvn -T`), and a shared container would let
concurrent modules corrupt each other's schema.

## Usage

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MyIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wireProps(DynamicPropertyRegistry registry) {
        registerDataSource(registry);                      // datasource from singleton PG
        registry.add("my.extra.url", () -> "http://.."); // module-specific extras
    }

    @BeforeAll
    static void setup() {
        applySchema("test-schema.sql"); // idempotent — IF NOT EXISTS
    }
}
```

**Remove from subclass:** `@Testcontainers`, `@Container PostgreSQLContainer`, and the
datasource lines inside `@DynamicPropertySource`. Spring does not pick up inherited
static `@DynamicPropertySource` methods, so call `registerDataSource(registry)` explicitly.

## Container reuse is OFF — do not re-enable it

The build runs modules in parallel (`mvn -T4` local, `-T2` on CI). Testcontainers **reuse**
(`testcontainers.reuse.enable=true` + `withReuse(true)`) is **incompatible** with that: parallel
modules would share one container and corrupt each other's schema (measured — a `bus.outbox` race).
So reuse is intentionally off and each module gets its own isolated container. Don't add the reuse
flag or `withReuse(true)` back. Rationale + numbers: [`plans/migration-25-boot4.md`](../../plans/migration-25-boot4.md) §Build/CI performance.

## Consumers

Every Testcontainers-backed integration test extends this base — ~30 IT classes across
`platform/*` (memory/media/scheduler/profile/notifier/conversation) and the domain
`domains/*/mcp-*` modules (introduced #202, adopted repo-wide).
