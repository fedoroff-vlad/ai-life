package dev.fedorov.ailife.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.scheduler.domain.ScheduleService;
import dev.fedorov.ailife.scheduler.tick.ScheduleTick;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

/**
 * Stage-1 E2E closer — proves the scheduler → orchestrator → calendar-agent **wire
 * contract** survives serialisation round-trips through real HTTP boundaries.
 *
 * <p><b>Why this lives in scheduler-service and not in a dedicated {@code tests/e2e-stage1}
 * module</b> (deviation from the deferred-work plan): packaging the apps with
 * {@code spring-boot-maven-plugin} default config replaces the main artifact with the fat
 * jar, so a downstream test module can't depend on the apps as plain Maven artifacts
 * without flipping every service to {@code classifier=exec} (and patching every
 * Dockerfile). That refactor is out of scope here; instead we exercise the same wire
 * chain from inside scheduler-service's test set with the real scheduler Spring context
 * plus two MockWebServers — orchestrator (with a forwarding dispatcher) and a stand-in
 * for calendar-agent's trigger endpoint. The {@link AgentWakeRequest} type from
 * {@code libs/contracts} is the actual bridge being asserted (calendar-agent's
 * {@code TriggerController} accepts it verbatim — see calendar-agent
 * {@code @PostMapping("/{kind}")} signature), so a serialisation mismatch in either
 * direction would fail this test.
 *
 * <p>Each individual seam already has its own integration test
 * ({@link SchedulerIntegrationTest}, {@code WakeDispatchTest} in orchestrator,
 * {@code TriggerControllerTest} in calendar-agent); this test composes them.
 */
@SpringBootTest(properties = "scheduler.tick-millis=3600000")
@Testcontainers
class E2EStage1ClosingFlowTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife").withUsername("ailife").withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    static MockWebServer orchestrator;
    static MockWebServer calendarAgent;
    static UUID householdId;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        try {
            calendarAgent = new MockWebServer();
            calendarAgent.start();
            orchestrator = new MockWebServer();
            // Orchestrator's job: deserialise the wake into AgentWakeRequest, forward it
            // **verbatim** to calendar-agent's trigger endpoint, then 202 the scheduler.
            // This mirrors what RemoteAgent.wake() does in real orchestrator code.
            orchestrator.setDispatcher(forwardingDispatcher());
            orchestrator.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mock web servers", e);
        }
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("scheduler.orchestrator-base-url",
                () -> "http://localhost:" + orchestrator.getPort());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient FORWARDER = new OkHttpClient();

    private static Dispatcher forwardingDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!"/v1/agents/wake".equals(request.getPath())) {
                    return new MockResponse().setResponseCode(404);
                }
                try {
                    // Buffer.clone() preserves the original — the test's takeRequest()
                    // will read it again later, and readUtf8() consumes.
                    String rawBody = request.getBody().clone().readUtf8();
                    AgentWakeRequest wake = MAPPER.readValue(rawBody, AgentWakeRequest.class);
                    String url = "http://localhost:" + calendarAgent.getPort()
                            + "/agents/" + wake.agent() + "/triggers/" + wake.kind();
                    Request forward = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(
                                    rawBody, okhttp3.MediaType.get("application/json")))
                            .build();
                    try (Response ignored = FORWARDER.newCall(forward).execute()) {
                        // calendar-agent stub always 202s — see the per-test enqueue.
                    }
                    return new MockResponse().setResponseCode(202);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500)
                            .setBody("dispatcher failure: " + e.getMessage());
                }
            }
        };
    }

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", householdId, "h");
    }

    @Autowired ScheduleService service;
    @Autowired ScheduleTick tick;
    @Autowired ObjectMapper json;

    @Test
    void wakePayloadSurvivesOrchestratorHopIntoCalendarAgentTrigger() throws Exception {
        UUID personId = UUID.randomUUID();
        ObjectNode payload = json.createObjectNode().put("personId", personId.toString());
        ScheduleDto due = service.create(new CreateScheduleRequest(
                householdId, "calendar", "birthday.greet", null,
                Instant.now().minus(1, ChronoUnit.MINUTES), payload));

        // calendar-agent stub: 1 response per inbound trigger.
        calendarAgent.enqueue(new MockResponse().setResponseCode(202));

        tick.tick();

        // Hop 1: scheduler → orchestrator
        RecordedRequest wake = orchestrator.takeRequest();
        assertThat(wake.getMethod()).isEqualTo("POST");
        assertThat(wake.getPath()).isEqualTo("/v1/agents/wake");
        AgentWakeRequest wakeBody = MAPPER.readValue(
                wake.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(wakeBody.agent()).isEqualTo("calendar");
        assertThat(wakeBody.kind()).isEqualTo("birthday.greet");
        assertThat(wakeBody.scheduleId()).isEqualTo(due.id());
        assertThat(wakeBody.householdId()).isEqualTo(householdId);
        assertThat(wakeBody.payload().path("personId").asText()).isEqualTo(personId.toString());

        // Hop 2: orchestrator (forwarding dispatcher) → calendar-agent trigger
        RecordedRequest trigger = calendarAgent.takeRequest();
        assertThat(trigger.getMethod()).isEqualTo("POST");
        assertThat(trigger.getPath()).isEqualTo("/agents/calendar/triggers/birthday.greet");
        // Calendar-agent's TriggerController binds the body to AgentWakeRequest verbatim;
        // assert it deserialises cleanly on this side too — that's the actual contract bridge.
        AgentWakeRequest triggerBody = MAPPER.readValue(
                trigger.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(triggerBody.scheduleId()).isEqualTo(wakeBody.scheduleId());
        assertThat(triggerBody.householdId()).isEqualTo(wakeBody.householdId());
        assertThat(triggerBody.agent()).isEqualTo(wakeBody.agent());
        assertThat(triggerBody.kind()).isEqualTo(wakeBody.kind());
        JsonNode triggerPayload = triggerBody.payload();
        assertThat(triggerPayload.path("personId").asText()).isEqualTo(personId.toString());
    }
}
