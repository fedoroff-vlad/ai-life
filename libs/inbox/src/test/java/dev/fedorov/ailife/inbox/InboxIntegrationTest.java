package dev.fedorov.ailife.inbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Standalone Testcontainers test (manages its own PG, does not extend AbstractPostgresIntegrationTest),
// so it carries @Tag("it") directly to land in the failsafe slow lane. See migration-25-boot4.md §fast/slow split.
@Tag("it")
@Testcontainers
class InboxIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql")
            .withReuse(true);

    private final DriverManagerDataSource ds = dataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private PostgresInboxRedriver redriver;

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @AfterEach
    void cleanup() {
        if (redriver != null) {
            redriver.close();
        }
        jdbc.update("DELETE FROM bus.inbox");
    }

    private String status(String dedupKey) {
        return jdbc.queryForObject("SELECT status FROM bus.inbox WHERE dedup_key = ?", String.class, dedupKey);
    }

    private int attempts(String dedupKey) {
        return jdbc.queryForObject("SELECT attempts FROM bus.inbox WHERE dedup_key = ?", Integer.class, dedupKey);
    }

    /** Fast redrive: no grace/backoff so the poll picks the row up immediately. */
    private InboxProperties fastProps(int maxAttempts) {
        InboxProperties p = new InboxProperties();
        p.setPollInterval(Duration.ofMillis(200));
        p.setInitialDelay(Duration.ZERO);
        p.setBackoff(Duration.ofMillis(200));
        p.setMaxBackoff(Duration.ofSeconds(1));
        p.setMaxAttempts(maxAttempts);
        return p;
    }

    @Test
    void recordDedupsOnKey() {
        assertThat(new InboxWriter(jdbc, Duration.ZERO).record("u1", "{\"a\":1}")).isTrue();
        // A re-recorded update_id (Telegram re-delivery / redrive re-entry) must not double-persist.
        assertThat(new InboxWriter(jdbc, Duration.ZERO).record("u1", "{\"a\":2}")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bus.inbox WHERE dedup_key = 'u1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void redriveDeliversAPendingRowExactlyOnce() {
        // The synchronous ingress attempt failed → the row stays PENDING (downstream was down).
        var writer = new InboxWriter(jdbc, Duration.ZERO);
        writer.record("u42", "{\"chatId\":7}");

        List<String> delivered = new CopyOnWriteArrayList<>();
        redriver = new PostgresInboxRedriver(ds, fastProps(6),
                msg -> delivered.add(msg.dedupKey()), DeadLetterHandler.noop());
        redriver.start();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(delivered).containsExactly("u42");
            assertThat(status("u42")).isEqualTo("PROCESSED");
        });
    }

    @Test
    void redriveRetriesTransientFailureThenDelivers() {
        var writer = new InboxWriter(jdbc, Duration.ZERO);
        writer.record("u7", "{}");

        AtomicInteger calls = new AtomicInteger();
        redriver = new PostgresInboxRedriver(ds, fastProps(6), msg -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("orchestrator down");
            }
        }, DeadLetterHandler.noop());
        redriver.start();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status("u7")).isEqualTo("PROCESSED");
            assertThat(calls.get()).isEqualTo(3);
        });
    }

    @Test
    void poisonMessageGoesDeadAndFiresDeadLetterOnce() {
        var writer = new InboxWriter(jdbc, Duration.ZERO);
        writer.record("poison", "{}");

        List<String> deadLettered = new CopyOnWriteArrayList<>();
        redriver = new PostgresInboxRedriver(ds, fastProps(3),
                msg -> { throw new IllegalStateException("always fails"); },
                msg -> deadLettered.add(msg.dedupKey()));
        redriver.start();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status("poison")).isEqualTo("DEAD");
            assertThat(attempts("poison")).isEqualTo(3);
            assertThat(deadLettered).containsExactly("poison");
        });
    }

    @Test
    void markProcessedKeepsRedriverOffTheRow() {
        // The synchronous ingress attempt succeeded and marked the row PROCESSED — the redriver must skip it.
        var writer = new InboxWriter(jdbc, Duration.ZERO);
        writer.record("u9", "{}");
        writer.markProcessed("u9");

        List<String> delivered = new CopyOnWriteArrayList<>();
        redriver = new PostgresInboxRedriver(ds, fastProps(6), msg -> delivered.add(msg.dedupKey()),
                DeadLetterHandler.noop());
        redriver.start();

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(delivered).isEmpty());
        assertThat(status("u9")).isEqualTo("PROCESSED");
    }
}
