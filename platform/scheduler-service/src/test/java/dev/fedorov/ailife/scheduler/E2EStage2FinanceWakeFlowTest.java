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
 * Stage-2 E2E closer — proves the scheduler → orchestrator → **finance-agent** wire contract for the
 * {@code budget.alert} wake survives serialisation across real HTTP boundaries. Mirrors
 * {@link E2EStage1ClosingFlowTest} (calendar) / {@link E2EStage3TasksWakeFlowTest} (tasks) for the
 * finance domain.
 *
 * <p>Unlike the tasks weekly-review wake (null payload), the budget alert carries a real payload —
 * {@code {categoryId, period}} (PR27b's {@code SchedulerClient.register} shape). finance-agent's
 * {@code TriggerController.enrichIfNeeded} turns those two keys into the full skill payload at
 * trigger time by calling mcp-finance's {@code /internal/budget-status}, so this test pins that the
 * two keys reach the agent intact. A regression that dropped/renamed them would fail here.
 *
 * <p>Same harness reasoning as the Stage-1 closer (see its javadoc): the fat-jar packaging blocks a
 * true all-real-services module, so scheduler-service's real Spring context runs plus two
 * MockWebServers — orchestrator (forwarding dispatcher) and a finance-agent trigger stand-in. A
 * past {@code runAt} gives a deterministic single tick; cron vs one-shot is orthogonal to the wake
 * contract.
 */
@SpringBootTest(properties = "scheduler.tick-millis=3600000")
class E2EStage2FinanceWakeFlowTest extends AbstractPostgresIntegrationTest {


    static MockWebServer orchestrator;
    static MockWebServer financeAgent;
    static UUID householdId;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        registerDataSource(r);
        try {
            financeAgent = new MockWebServer();
            financeAgent.start();
            orchestrator = new MockWebServer();
            orchestrator.setDispatcher(forwardingDispatcher());
            orchestrator.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mock web servers", e);
        }        r.add("scheduler.orchestrator-base-url",
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
                    String url = "http://localhost:" + financeAgent.getPort()
                            + "/agents/" + wake.agent() + "/triggers/" + wake.kind();
                    Request forward = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(
                                    rawBody, okhttp3.MediaType.get("application/json")))
                            .build();
                    try (Response ignored = FORWARDER.newCall(forward).execute()) {
                        // finance-agent stub always 202s — see the per-test enqueue.
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
    void budgetAlertWakeReachesFinanceAgentTriggerWithCategoryAndPeriod() throws Exception {
        // PR27b shape: budget.alert payload carries {categoryId, period}; the agent enriches the rest.
        UUID categoryId = UUID.randomUUID();
        ObjectNode payload = json.createObjectNode();
        payload.put("categoryId", categoryId.toString());
        payload.put("period", "month");
        ScheduleDto due = service.create(new CreateScheduleRequest(
                householdId, "finance", "budget.alert", null,
                Instant.now().minus(1, ChronoUnit.MINUTES), payload));

        financeAgent.enqueue(new MockResponse().setResponseCode(202));

        tick.tick();

        // Hop 1: scheduler → orchestrator
        RecordedRequest wake = orchestrator.takeRequest();
        assertThat(wake.getMethod()).isEqualTo("POST");
        assertThat(wake.getPath()).isEqualTo("/v1/agents/wake");
        AgentWakeRequest wakeBody = MAPPER.readValue(
                wake.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(wakeBody.agent()).isEqualTo("finance");
        assertThat(wakeBody.kind()).isEqualTo("budget.alert");
        assertThat(wakeBody.scheduleId()).isEqualTo(due.id());
        assertThat(wakeBody.householdId()).isEqualTo(householdId);
        assertThat(wakeBody.payload().path("categoryId").asText()).isEqualTo(categoryId.toString());
        assertThat(wakeBody.payload().path("period").asText()).isEqualTo("month");

        // Hop 2: orchestrator (forwarding) → finance-agent trigger endpoint
        RecordedRequest trigger = financeAgent.takeRequest();
        assertThat(trigger.getMethod()).isEqualTo("POST");
        assertThat(trigger.getPath()).isEqualTo("/agents/finance/triggers/budget.alert");
        AgentWakeRequest triggerBody = MAPPER.readValue(
                trigger.getBody().readUtf8(), AgentWakeRequest.class);
        assertThat(triggerBody.scheduleId()).isEqualTo(wakeBody.scheduleId());
        assertThat(triggerBody.householdId()).isEqualTo(householdId);
        assertThat(triggerBody.agent()).isEqualTo("finance");
        assertThat(triggerBody.kind()).isEqualTo("budget.alert");
        assertThat(triggerBody.payload().path("categoryId").asText()).isEqualTo(categoryId.toString());
        assertThat(triggerBody.payload().path("period").asText()).isEqualTo("month");
    }
}
