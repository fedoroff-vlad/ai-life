package dev.fedorov.ailife.test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Testcontainers PG base for integration tests — one isolated {@code pgvector} container per module JVM.
 *
 * <p>Container reuse is intentionally OFF: the build runs modules in parallel ({@code mvn -T}), and a
 * shared/reused container would let concurrent modules corrupt each other's schema. Giving each module
 * its own container is what keeps {@code -T} safe. (Do NOT re-add {@code withReuse(true)} +
 * {@code testcontainers.reuse.enable=true} — that combination breaks the parallel build; measured.)
 * See {@code plans/migration-25-boot4.md} §Build/CI performance.
 *
 * <p>Within a single module the static container is shared across that module's test classes.
 *
 * <p>Subclass usage:
 *   1. extend this class (remove @Testcontainers + @Container from the subclass)
 *   2. call applySchema("test-schema.sql") in a @BeforeAll (schema is idempotent — IF NOT EXISTS)
 *   3. keep module-specific @DynamicPropertySource entries; datasource is wired here
 */
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("ailife")
                .withUsername("ailife")
                .withPassword("ailife");
        POSTGRES.start();
    }

    /**
     * Wire datasource properties. Call from the subclass's @DynamicPropertySource:
     * <pre>
     *   @DynamicPropertySource
     *   static void wireProps(DynamicPropertyRegistry r) {
     *       registerDataSource(r);
     *       r.add("my.extra.prop", () -> "value");
     *   }
     * </pre>
     * Spring does not pick up inherited static @DynamicPropertySource methods,
     * so each subclass must call this explicitly.
     */
    public static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Apply a test schema from the caller's classpath. Safe to call multiple
     * times — all DDL in test schemas uses IF NOT EXISTS.
     */
    public static void applySchema(String classpathResource) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(classpathResource));
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply test schema: " + classpathResource, e);
        }
    }
}
