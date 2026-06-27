# test-support

**Status:** Active (Stage 7+, introduced #202)

Shared test utilities for integration tests across all modules.

## Purpose

`AbstractPostgresIntegrationTest` — base class that starts a single `pgvector/pgvector:pg16`
Testcontainers container with `withReuse(true)`. All subclasses in a JVM share the same
container; with reuse enabled across JVMs, separate Maven modules reuse the running container
instead of spinning a new one each time, cutting full-suite container startups significantly.

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

## Enabling reuse on CI

Testcontainers reuse requires `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`.
The CI workflow writes this before running `mvn verify`.

## Consumers

- `domains/finance/mcp-finance` (pilot)
- _(remaining modules migrated in #202 follow-up)_
