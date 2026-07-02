package dev.fedorov.ailife.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.scheduler.domain.ScheduleService;
import dev.fedorov.ailife.scheduler.tick.ScheduleTick;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-f2 E2E closer — proves the scheduler → orchestrator → **briefing-agent** wire contract for the
 * {@code briefing.digest} morning wake survives serialisation across real HTTP boundaries. Mirrors
 * {@link E2EStage2FinanceWakeFlowTest} (finance) for the briefing domain.
 *
 * <p>mcp-briefing's {@code SchedulerClient.register} (BR-f2) posts a schedule whose payload carries
 * just {@code ownerId} — the person the digest is for. briefing-agent's {@code TriggerController}
 * (BR-f1) reuses {@code BriefingComposer.digest} for that owner (empty user text) and delivers via
 * notifier. This test pins that the {@code ownerId} key reaches the agent trigger intact; a
 * regression that dropped/renamed it would fail here.
 *
 * <p>Same harness reasoning as the finance closer: the fat-jar packaging blocks a true
 * all-real-services module, so scheduler-service's real Spring context runs plus two MockWebServers —
 * orchestrator (forwarding dispatcher) and a briefing-agent trigger stand-in. A past {@code runAt}
 * gives a deterministic single tick; cron vs one-shot is orthogonal to the wake contract.
 */
@SpringBootTest(properties = "scheduler.tick-millis=3600000")
class E2EBriefingWakeFlowTest extends AbstractPostgresIntegrationTest {

    static MockWebServer orchestrator;
    static MockWebServer briefingAgent;
    static UUID householdId;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        registerDataSource(r);
        try {
            briefingAgent = new MockWebServer();
            briefingAgent.start();
            orchestrator = new MockWebServer();
            orchestrator.setDispatcher(forwardingDispatcher());
            orchestrator.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mock web servers", e);
        }
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
                    String url = "http://localhost:" + briefingAgent.getPort()
                            + "/agents/" + wake.agent() + "/triggers/" + wake.kind();
                    Request forward = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(
                                    rawBody, okhttp3.MediaType.get("application/json")))
                            .build();
                    try (Response ignored = FORWARDER.newCall(forward).execute()) {
                        // briefing-agent stub always 202s — see the per-test enqueue.
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
        applySchema("test-schema.sql");
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", householdId, "h");
    }

    @Autowired ScheduleService service;
    @Autowired ScheduleTick tick;
    @Autowired ObjectMapper json;

    @Test
    void briefingDigestWakeReachesBriefingAgentTriggerWithOwnerId() throws Exception {
        // BR-f2 shape: briefing.digest payload carries {ownerId}; the agent runs that person's digest.
        UUID ownerId = UUID.randomUUID();
        ObjectNode payload = json.createObjectNode();
        payload.put("ownerId", ownerId.toString());
        ScheduleDto due = service.create(new CreateScheduleRequest(
                householdId, "briefing", "briefing.digest", null,
                Instant.now().minus(1, ChronoUnit.MINUTES), payload));

        briefingAgent.enqueue(new MockResponse().setResponseCode(202));

        tick.tick();

        // Hop 1: scheduler → orchestrator
        RecordedRequest wake = orchestrator.takeRequest();
        assertThat(wake.getMethod()).isEqualTo("POST");
        assertThat(wake.getPath()).isEqualTo("/v1/agents/wake");
        AgentWakeRequest wakeBody = MAPPER.readValue(
                wake.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(wakeBody.agent()).isEqualTo("briefing");
        assertThat(wakeBody.kind()).isEqualTo("briefing.digest");
        assertThat(wakeBody.scheduleId()).isEqualTo(due.id());
        assertThat(wakeBody.householdId()).isEqualTo(householdId);
        assertThat(wakeBody.payload().path("ownerId").asText()).isEqualTo(ownerId.toString());

        // Hop 2: orchestrator (forwarding) → briefing-agent trigger endpoint
        RecordedRequest trigger = briefingAgent.takeRequest();
        assertThat(trigger.getMethod()).isEqualTo("POST");
        assertThat(trigger.getPath()).isEqualTo("/agents/briefing/triggers/briefing.digest");
        AgentWakeRequest triggerBody = MAPPER.readValue(
                trigger.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(triggerBody.scheduleId()).isEqualTo(wakeBody.scheduleId());
        assertThat(triggerBody.householdId()).isEqualTo(householdId);
        assertThat(triggerBody.agent()).isEqualTo("briefing");
        assertThat(triggerBody.kind()).isEqualTo("briefing.digest");
        assertThat(triggerBody.payload().path("ownerId").asText()).isEqualTo(ownerId.toString());
    }
}
