package dev.fedorov.ailife.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.scheduler.domain.ScheduleRepository;
import dev.fedorov.ailife.scheduler.domain.ScheduleService;
import dev.fedorov.ailife.scheduler.tick.ScheduleTick;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        // disable the @Scheduled tick — we invoke ScheduleTick manually
        "scheduler.tick-millis=3600000"
})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchedulerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife").withUsername("ailife").withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    static MockWebServer orchestrator;
    static UUID householdId;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        // MockWebServer must be alive before property bindings resolve —
        // @DynamicPropertySource runs before @BeforeAll, so we start it here.
        try {
            orchestrator = new MockWebServer();
            orchestrator.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mock orchestrator", e);
        }
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("scheduler.orchestrator-base-url",
                () -> "http://localhost:" + orchestrator.getPort());
    }

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", householdId, "h");
    }

    @Autowired ScheduleService service;
    @Autowired ScheduleRepository repo;
    @Autowired ScheduleTick tick;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Order(1)
    void cronScheduleIsAccepted() {
        ObjectNode payload = json.createObjectNode().put("personId", "maria");
        ScheduleDto created = service.create(new CreateScheduleRequest(
                householdId, "calendar", "birthday.greet", "0 0 8 * * *", null, payload));

        assertThat(created.id()).isNotNull();
        assertThat(created.cron()).isEqualTo("0 0 8 * * *");
        assertThat(created.nextRunTs()).isAfter(Instant.now());
        assertThat(created.payload().get("personId").asText()).isEqualTo("maria");
    }

    @Test
    @Order(2)
    void tickWakesAgentAndDisablesOneShot() throws InterruptedException {
        // Past one-shot — should fire immediately on next tick.
        ObjectNode payload = json.createObjectNode().put("note", "ping");
        ScheduleDto due = service.create(new CreateScheduleRequest(
                householdId, "calendar", "birthday.greet", null,
                Instant.now().minus(1, ChronoUnit.MINUTES), payload));

        orchestrator.enqueue(new MockResponse().setResponseCode(204));

        tick.tick();

        RecordedRequest wake = orchestrator.takeRequest();
        assertThat(wake.getPath()).isEqualTo("/v1/agents/wake");
        String body = wake.getBody().readUtf8();
        assertThat(body).contains("\"agent\":\"calendar\"");
        assertThat(body).contains("\"kind\":\"birthday.greet\"");
        assertThat(body).contains("\"note\":\"ping\"");

        var stored = repo.findById(due.id()).orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getLastRunTs()).isNotNull();

        // Producer side (B2): the wake also emitted a schedule.fired event to the outbox.
        Integer fired = jdbc.queryForObject("""
                SELECT count(*) FROM bus.outbox
                WHERE topic = 'schedule.fired' AND payload->>'scheduleId' = ?
                """, Integer.class, due.id().toString());
        assertThat(fired).isEqualTo(1);
        String firedKind = jdbc.queryForObject("""
                SELECT payload->>'kind' FROM bus.outbox
                WHERE topic = 'schedule.fired' AND payload->>'scheduleId' = ?
                """, String.class, due.id().toString());
        assertThat(firedKind).isEqualTo("birthday.greet");
    }

    @Test
    @Order(3)
    void pauseAndResumeFlip() {
        ScheduleDto s = service.create(new CreateScheduleRequest(
                householdId, "calendar", "ping", "0 0 9 * * *", null, null));

        assertThat(service.setEnabled(s.id(), false)).isTrue();
        assertThat(repo.findById(s.id()).orElseThrow().isEnabled()).isFalse();

        assertThat(service.setEnabled(s.id(), true)).isTrue();
        assertThat(repo.findById(s.id()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @Order(4)
    void invalidCronIs400Equivalent() {
        try {
            service.create(new CreateScheduleRequest(
                    householdId, "calendar", "ping", "this is not cron", null, null));
            assertThat(true).isFalse();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("Invalid cron");
        }
    }
}
