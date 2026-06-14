package dev.fedorov.ailife.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Stage-3 E2E closer — proves the scheduler → orchestrator → **tasks-agent** wire contract for the
 * GTD {@code weekly.review} wake survives serialisation across real HTTP boundaries. Mirrors
 * {@link E2EStage1ClosingFlowTest} (calendar) for the tasks domain.
 *
 * <p>Specifically asserts PR61's design: the weekly-review schedule is registered with a null
 * payload (normalised to an empty {@code {}} by the column default), so {@code householdId} travels
 * on the schedule row itself and must reach the agent via {@link AgentWakeRequest#householdId()}
 * (that's what tasks-agent's {@code TriggerController} reads to enrich from {@code /internal/review}).
 * A regression that dropped householdId off the row, or mis-bound the wake DTO, would fail here.
 *
 * <p>Same harness reasoning as the Stage-1 closer (see its javadoc): the monorepo's fat-jar
 * packaging blocks a true all-real-services module, so we run scheduler-service's real Spring
 * context plus two MockWebServers — orchestrator (forwarding dispatcher) and a tasks-agent
 * trigger-endpoint stand-in. A cron vs one-shot schedule is orthogonal to the wake contract, so we
 * use a past {@code runAt} for a deterministic single tick.
 */
@SpringBootTest(properties = "scheduler.tick-millis=3600000")
@Testcontainers
class E2EStage3TasksWakeFlowTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife").withUsername("ailife").withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    static MockWebServer orchestrator;
    static MockWebServer tasksAgent;
    static UUID householdId;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        try {
            tasksAgent = new MockWebServer();
            tasksAgent.start();
            orchestrator = new MockWebServer();
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

    /** Orchestrator stand-in: deserialise the wake, forward it verbatim to the agent's trigger. */
    private static Dispatcher forwardingDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!"/v1/agents/wake".equals(request.getPath())) {
                    return new MockResponse().setResponseCode(404);
                }
                try {
                    String rawBody = request.getBody().clone().readUtf8();
                    AgentWakeRequest wake = MAPPER.readValue(rawBody, AgentWakeRequest.class);
                    String url = "http://localhost:" + tasksAgent.getPort()
                            + "/agents/" + wake.agent() + "/triggers/" + wake.kind();
                    Request forward = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(
                                    rawBody, okhttp3.MediaType.get("application/json")))
                            .build();
                    try (Response ignored = FORWARDER.newCall(forward).execute()) {
                        // tasks-agent stub always 202s — see the per-test enqueue.
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

    @Test
    void weeklyReviewWakeReachesTasksAgentTriggerWithHouseholdAndNullPayload() throws Exception {
        // PR61 shape: weekly.review registered with a NULL payload; householdId rides the row.
        ScheduleDto due = service.create(new CreateScheduleRequest(
                householdId, "tasks", "weekly.review", null,
                Instant.now().minus(1, ChronoUnit.MINUTES), null));

        tasksAgent.enqueue(new MockResponse().setResponseCode(202));

        tick.tick();

        // Hop 1: scheduler → orchestrator
        RecordedRequest wake = orchestrator.takeRequest();
        assertThat(wake.getMethod()).isEqualTo("POST");
        assertThat(wake.getPath()).isEqualTo("/v1/agents/wake");
        AgentWakeRequest wakeBody = MAPPER.readValue(
                wake.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(wakeBody.agent()).isEqualTo("tasks");
        assertThat(wakeBody.kind()).isEqualTo("weekly.review");
        assertThat(wakeBody.scheduleId()).isEqualTo(due.id());
        assertThat(wakeBody.householdId()).isEqualTo(householdId);
        // A null payload is normalised to an empty {} by the column default (NOT NULL DEFAULT
        // '{}'), so householdId — carried on the schedule row — is the actual data the agent reads.
        assertThat(wakeBody.payload()).isNotNull();
        assertThat(wakeBody.payload().isEmpty()).isTrue();

        // Hop 2: orchestrator (forwarding) → tasks-agent trigger endpoint
        RecordedRequest trigger = tasksAgent.takeRequest();
        assertThat(trigger.getMethod()).isEqualTo("POST");
        assertThat(trigger.getPath()).isEqualTo("/agents/tasks/triggers/weekly.review");
        AgentWakeRequest triggerBody = MAPPER.readValue(
                trigger.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(triggerBody.scheduleId()).isEqualTo(wakeBody.scheduleId());
        assertThat(triggerBody.householdId()).isEqualTo(householdId);
        assertThat(triggerBody.agent()).isEqualTo("tasks");
        assertThat(triggerBody.kind()).isEqualTo("weekly.review");
        assertThat(triggerBody.payload().isEmpty()).isTrue();
    }
}
