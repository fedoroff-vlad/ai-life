package dev.fedorov.ailife.agents.coordinator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agents.coordinator.flow.SpecialistBriefs;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mandatory <b>multi-domain</b> end-to-end closer for #477 (Track I / I2) — proves real agent-led
 * cross-domain coordination over real HTTP boundaries, not just the single-domain coordinator demo. ONE
 * real coordinator-agent Spring context; MockWebServers stand in for the three hops the coordinator uses
 * to fan out — memory-service, llm-gateway (FAST planning + DEFAULT synthesis) and the <b>orchestrator
 * hub</b> ({@code POST /v1/agents/invoke}) that <i>forwards</i> each specialist's read-only {@code brief}.
 *
 * <p>Asserts the #477 acceptance invariants on a real multi-agent path:
 * <ul>
 *   <li><b>fan-out → one synthesized answer</b> — a "спланируй выходные" ask picks ≥2 specialists, each
 *       brief is gathered live through the hub, and the single DEFAULT synthesis is grounded in <i>all</i>
 *       of them (the synthesis prompt carries every specialist's answer);</li>
 *   <li><b>no direct agent-to-agent calls</b> — the coordinator reaches specialists <i>only</i> through the
 *       orchestrator hub (the only specialist transport wired is {@code /v1/agents/invoke}); every recorded
 *       hub call is a {@code brief} action for a rostered specialist;</li>
 *   <li><b>per-source soft-fail</b> — when one specialist's hub invoke 500s, the flow still returns one
 *       grounded reply from the survivors, never a 500.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class E2ECoordinateMultiDomainTest {

    static MockWebServer llmGateway;
    static MockWebServer memoryService;
    static MockWebServer orchestrator;

    private static final String FINANCE_BRIEF = "Бюджет на выходные: свободно 5000 ₽.";
    private static final String CALENDAR_BRIEF = "Суббота и воскресенье свободны, ничего не запланировано.";
    private static final String TASKS_BRIEF = "Из дел: продлить страховку и забрать посылку.";
    private static final String SYNTHESIS =
            "На выходных всё свободно — бюджет 5000 ₽, успей продлить страховку и забрать посылку.";

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        memoryService = new MockWebServer();
        orchestrator = new MockWebServer();
        llmGateway.start();
        memoryService.start();
        orchestrator.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        memoryService.shutdown();
        orchestrator.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("coordinator-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("coordinator-agent.orchestrator-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        // One-shot: this closer asserts the fan-out synthesis, not the E-later re-gather loop.
        r.add("coordinator-agent.max-rounds", () -> "1");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void multiDomainAskFansOutThroughTheHubToOneGroundedSynthesis() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memoryService.setDispatcher(recallDispatcher(householdId, userId));
        // The FAST planner picks all three real specialists; the DEFAULT synthesis turn follows. Capture the
        // synthesis prompt so we can prove it was grounded in every specialist's brief.
        AtomicReference<String> synthesisBody = new AtomicReference<>();
        llmGateway.setDispatcher(llmDispatcher("[\"finance\", \"calendar\", \"tasks\"]", synthesisBody));
        // The hub forwards each specialist's brief. Record who was invoked to prove coordination went
        // through the hub (no direct agent-to-agent call).
        List<String> hubInvokes = new CopyOnWriteArrayList<>();
        orchestrator.setDispatcher(hubDispatcher(hubInvokes, java.util.Set.of()));

        IntentResponse resp = coordinate(householdId, userId, "спланируй мне выходные с учётом бюджета и дел");

        // One synthesized answer, grounded in ALL specialists.
        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("coordinator");
        assertThat(resp.llmModel()).isEqualTo("mock-large");
        assertThat(resp.text()).isEqualTo(SYNTHESIS);
        assertThat(synthesisBody.get())
                .contains(FINANCE_BRIEF).contains(CALENDAR_BRIEF).contains(TASKS_BRIEF);

        // Invariant: coordination reached the specialists ONLY through the hub, one brief invoke each.
        assertThat(hubInvokes).containsExactlyInAnyOrder(
                "finance:brief", "calendar:brief", "tasks:brief");
    }

    @Test
    void oneSpecialistFailureDegradesInsteadOf500() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memoryService.setDispatcher(recallDispatcher(householdId, userId));
        AtomicReference<String> synthesisBody = new AtomicReference<>();
        llmGateway.setDispatcher(llmDispatcher("[\"finance\", \"calendar\", \"tasks\"]", synthesisBody));
        // tasks' hub invoke 500s; finance + calendar answer normally. Per-source soft-fail must degrade,
        // not fail the whole request.
        List<String> hubInvokes = new CopyOnWriteArrayList<>();
        orchestrator.setDispatcher(hubDispatcher(hubInvokes, java.util.Set.of("tasks")));

        IntentResponse resp = coordinate(householdId, userId, "спланируй мне выходные с учётом бюджета и дел");

        // Still one grounded reply — never a 500 — from the survivors; the failed specialist is omitted.
        assertThat(resp).isNotNull();
        assertThat(resp.text()).isEqualTo(SYNTHESIS);
        assertThat(synthesisBody.get())
                .contains(FINANCE_BRIEF).contains(CALENDAR_BRIEF).doesNotContain(TASKS_BRIEF);
        // All three were still invoked through the hub; only tasks soft-failed.
        assertThat(hubInvokes).containsExactlyInAnyOrder(
                "finance:brief", "calendar:brief", "tasks:brief");
    }

    private IntentResponse coordinate(UUID householdId, UUID userId, String text) {
        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                text, List.of(), "telegram", "e2e-md-1", Instant.now());
        return http.post().uri("/agents/coordinator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    /** memory-service: every recall returns one household-scoped hit. */
    private Dispatcher recallDispatcher(UUID householdId, UUID userId) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().equals("/v1/memories/recall")) {
                    try {
                        return jsonResponse(json.writeValueAsString(List.of(new RecallMemoryHit(
                                memoryOf(householdId, userId, "Owner likes a quiet weekend at home"), 0.20))));
                    } catch (Exception e) {
                        return new MockResponse().setResponseCode(500);
                    }
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    /**
     * llm-gateway: the FAST planning turn (recognised by {@link SpecialistBriefs#PLANNER_MARKER}) returns
     * the picked roster; any other {@code /v1/chat} is the DEFAULT synthesis turn — its prompt is captured
     * and it returns the final answer.
     */
    private Dispatcher llmDispatcher(String picksJson, AtomicReference<String> synthesisBody) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                try {
                    if (body.contains(SpecialistBriefs.PLANNER_MARKER)) {
                        return jsonResponse(json.writeValueAsString(new LlmChatResponse(
                                "mock-fast", picksJson, "stop", new LlmUsage(40, 8, 48))));
                    }
                    synthesisBody.set(body);
                    return jsonResponse(json.writeValueAsString(new LlmChatResponse(
                            "mock-large", SYNTHESIS, "stop", new LlmUsage(220, 60, 280))));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        };
    }

    /**
     * orchestrator hub: forwards a specialist {@code brief} invoke. Records {@code targetAgent:action} for
     * every call (the no-direct-call proof) and returns that specialist's canned brief answer — unless the
     * specialist is in {@code fail}, in which case it 500s (per-source soft-fail).
     */
    private Dispatcher hubDispatcher(List<String> invokes, java.util.Set<String> fail) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() == null || !request.getPath().equals("/v1/agents/invoke")) {
                    return new MockResponse().setResponseCode(404);
                }
                try {
                    JsonNode req = json.readTree(request.getBody().readUtf8());
                    String target = req.path("targetAgent").asString("");
                    String action = req.path("action").asString("");
                    invokes.add(target + ":" + action);
                    if (fail.contains(target)) {
                        return new MockResponse().setResponseCode(500);
                    }
                    return jsonResponse(json.writeValueAsString(AgentActionResult.ok(brief(target))));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        };
    }

    private ObjectNode brief(String agent) {
        String answer = switch (agent) {
            case "finance" -> FINANCE_BRIEF;
            case "calendar" -> CALENDAR_BRIEF;
            case "tasks" -> TASKS_BRIEF;
            default -> "";
        };
        ObjectNode node = json.createObjectNode();
        node.put("agent", agent);
        node.put("answer", answer);
        return node;
    }

    private static MemoryDto memoryOf(UUID householdId, UUID userId, String text) {
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, "note", text, null, Instant.now());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
