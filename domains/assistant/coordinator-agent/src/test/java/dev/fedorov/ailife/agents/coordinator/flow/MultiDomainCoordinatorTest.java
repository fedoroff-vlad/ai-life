package dev.fedorov.ailife.agents.coordinator.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the coordinator's two entries through its HTTP surface (#290, Slice A + B2). Both funnel
 * through {@link MultiDomainCoordinator#run}: gather (second-brain recall + live specialist briefs) → one
 * synthesis on the shared Coordinator. MockWebServers stand in for memory-service, llm-gateway (both the
 * FAST planning turn and the DEFAULT synthesis turn, told apart by {@link SpecialistBriefs#PLANNER_MARKER}
 * in the request body), the orchestrator hub, profile-service, and notifier.
 *
 * <ul>
 *   <li><b>Reactive</b> ({@code /intent}): the synthesis request carries the recalled context (grounding),
 *       and the reply is the synthesized text. When the planner picks a specialist, its live {@code brief}
 *       answer is gathered through the hub and folded into the synthesis context (Slice B2).</li>
 *   <li><b>Proactive</b> ({@code /triggers/coordinator.surface}): a substantial synthesis is delivered to
 *       the owner; a thin one is dropped by the relevance gate (precision over volume).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MultiDomainCoordinatorTest {

    static MockWebServer memory;
    static MockWebServer llmGateway;
    static MockWebServer orchestrator;
    static MockWebServer profileService;
    static MockWebServer notifier;

    /** Per-test planner reply (a JSON array of specialist names) and synthesis reply, served by the llm dispatcher. */
    static volatile String plannerReply = "[]";
    static volatile String synthesisReply = "ok";
    /** Per-test self-check verdict (Slice E-later); default "sufficient" keeps the loop one-shot. */
    static volatile String assessorReply = "{\"sufficient\": true, \"missing\": \"\"}";

    @BeforeAll
    static void start() throws Exception {
        memory = new MockWebServer();
        llmGateway = new MockWebServer();
        orchestrator = new MockWebServer();
        profileService = new MockWebServer();
        notifier = new MockWebServer();
        for (MockWebServer s : List.of(memory, llmGateway, orchestrator, profileService, notifier)) {
            s.start();
        }
        // One dispatcher tells the three LLM turns apart by their system-prompt markers: the FAST planning
        // turn, the FAST self-check turn (Slice E-later), and the DEFAULT synthesis turn.
        llmGateway.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // clone() peeks the body without draining it, so takeRequest can still read it later.
                String body = request.getBody().clone().readUtf8();
                String content;
                if (body.contains(SpecialistBriefs.PLANNER_MARKER)) {
                    content = plannerReply;
                } else if (body.contains(SufficiencyAssessor.ASSESSOR_MARKER)) {
                    content = assessorReply;
                } else {
                    content = synthesisReply;
                }
                return jsonResponse(llmJson(content));
            }
        });
    }

    @AfterAll
    static void stop() throws Exception {
        for (MockWebServer s : List.of(memory, llmGateway, orchestrator, profileService, notifier)) {
            s.shutdown();
        }
    }

    @BeforeEach
    void reset() {
        plannerReply = "[]";
        synthesisReply = "ok";
        assessorReply = "{\"sufficient\": true, \"missing\": \"\"}";
    }

    /** Shared static servers → drain recorded requests between tests so negative assertions stay honest. */
    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(memory, llmGateway, orchestrator, profileService, notifier)) {
            while (s.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("coordinator-agent.memory-service-url", () -> "http://localhost:" + memory.getPort());
        r.add("coordinator-agent.orchestrator-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("coordinator-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("coordinator-agent.notifier-url", () -> "http://localhost:" + notifier.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void reactiveIntentSynthesizesGroundedInRecalledContext() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memory.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner is planning a trip to Georgia in August"), 0.12),
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner's travel budget note: keep it under 100k RUB"), 0.20)))));
        // Planner picks no specialist → memory-only synthesis (no hub call).
        plannerReply = "[]";
        synthesisReply = "Собрал по всем фронтам: план поездки в рамках бюджета.";

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "помоги свести всё про мою поездку и бюджет", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = http.post().uri("/agents/coordinator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("coordinator");
        assertThat(resp.text()).isEqualTo("Собрал по всем фронтам: план поездки в рамках бюджета.");

        // The synthesis turn (not the planning turn) carried the recalled second-brain context (grounding).
        String synthesisBody = takeSynthesisRequest();
        assertThat(synthesisBody).contains("Georgia").contains("under 100k RUB");
        // No specialist was picked → the hub was never called.
        assertThat(orchestrator.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void underConfidentDraftTriggersOneReGatherRound() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memory.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner is planning a birthday dinner"), 0.18)))));
        plannerReply = "[]"; // memory-only, keep the focus on the loop itself
        synthesisReply = "Черновик ответа про ужин.";
        // The self-check keeps judging the draft under-confident; max-rounds=2 caps it to ONE re-gather.
        assessorReply = "{\"sufficient\": false, \"missing\": \"точная дата ужина\"}";

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "помоги спланировать ужин", List.of(), "telegram", "rg", Instant.now());

        IntentResponse resp = http.post().uri("/agents/coordinator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).isEqualTo("Черновик ответа про ужин.");

        // Exactly two DEFAULT synthesis turns ran (round 1 + one re-gather); the second carried the prior
        // draft + the self-check's focus hint so the model refines rather than restarts.
        List<String> synthesisBodies = drainSynthesisBodies();
        assertThat(synthesisBodies).hasSize(2);
        assertThat(synthesisBodies.get(1))
                .contains("priorDraft")
                .contains("refineFocus")
                .contains("точная дата ужина");

        // Two gather rounds → the second brain was recalled twice.
        int recalls = 0;
        while (memory.takeRequest(300, TimeUnit.MILLISECONDS) != null) {
            recalls++;
        }
        assertThat(recalls).isEqualTo(2);
    }

    @Test
    void reactiveIntentFoldsPickedSpecialistBriefIntoSynthesis() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memory.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner's mother's birthday is next week"), 0.15)))));
        // Planner picks finance; the hub returns finance's read-only brief answer.
        plannerReply = "[\"finance\"]";
        synthesisReply = "Идея подарка маме в рамках бюджета на подарки.";
        orchestrator.setDispatcher(fixedJson(json.writeValueAsString(
                AgentActionResult.ok(json.createObjectNode()
                        .put("agent", "finance")
                        .put("answer", "The gifts budget has 15000 RUB left this month.")))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что подарить маме и хватит ли бюджета", List.of(), "telegram", "2", Instant.now());

        IntentResponse resp = http.post().uri("/agents/coordinator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).isEqualTo("Идея подарка маме в рамках бюджета на подарки.");

        // The hub was invoked with a read-only brief carrying the user's question.
        RecordedRequest hubReq = orchestrator.takeRequest(2, TimeUnit.SECONDS);
        assertThat(hubReq).isNotNull();
        assertThat(hubReq.getPath()).isEqualTo("/v1/agents/invoke");
        String hubBody = hubReq.getBody().readUtf8();
        assertThat(hubBody).contains("\"targetAgent\":\"finance\"")
                .contains("\"action\":\"brief\"")
                .contains("подарить маме");

        // The synthesis folded finance's live brief answer in alongside the memory recall.
        String synthesisBody = takeSynthesisRequest();
        assertThat(synthesisBody).contains("15000 RUB").contains("mother's birthday");
    }

    @Test
    void proactiveSurfaceDeliversASubstantialSynthesisToTheOwner() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        memory.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, ownerId,
                        "Owner wanted to reconnect with an old colleague about a side project"), 0.15)))));
        notifier.setDispatcher(ok());
        plannerReply = "[]";
        synthesisReply = "Идея: свяжись со старым коллегой по сайд-проекту — ты откладывал это.";

        post("coordinator.surface", wake(householdId, ownerId, null));

        RecordedRequest notifyReq = notifier.takeRequest(3, TimeUnit.SECONDS);
        assertThat(notifyReq).isNotNull();
        assertThat(notifyReq.getPath()).isEqualTo("/v1/notify");
        String body = notifyReq.getBody().readUtf8();
        assertThat(body).contains(ownerId.toString()).contains("свяжись со старым коллегой");
        // A known owner is notified directly — no household fan-out lookup.
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void proactiveSurfaceStaysSilentWhenNothingWorthSurfacing() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        memory.setDispatcher(fixedJson("[]"));
        notifier.setDispatcher(ok());
        plannerReply = "[]";
        // A thin synthesis (< relevance bar) must not become a notification.
        synthesisReply = "нет";

        post("coordinator.surface", wake(householdId, ownerId, null));

        // The synthesis ran (wake accepted), but nothing was delivered.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(notifier.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    /** Drain llm requests until the DEFAULT synthesis turn (skips the FAST planning + self-check turns). */
    private String takeSynthesisRequest() throws Exception {
        for (int i = 0; i < 6; i++) {
            RecordedRequest req = llmGateway.takeRequest(2, TimeUnit.SECONDS);
            if (req == null) {
                break;
            }
            String body = req.getBody().readUtf8();
            if (!body.contains(SpecialistBriefs.PLANNER_MARKER)
                    && !body.contains(SufficiencyAssessor.ASSESSOR_MARKER)) {
                return body;
            }
        }
        throw new AssertionError("no synthesis request reached llm-gateway");
    }

    /** Drain every llm request, returning only the DEFAULT synthesis bodies (skips planning + self-check). */
    private List<String> drainSynthesisBodies() throws Exception {
        List<String> bodies = new java.util.ArrayList<>();
        RecordedRequest req;
        while ((req = llmGateway.takeRequest(500, TimeUnit.MILLISECONDS)) != null) {
            String body = req.getBody().readUtf8();
            if (!body.contains(SpecialistBriefs.PLANNER_MARKER)
                    && !body.contains(SufficiencyAssessor.ASSESSOR_MARKER)) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    private void post(String kind, AgentWakeRequest req) {
        http.post().uri("/agents/coordinator/triggers/" + kind)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isAccepted();
    }

    private AgentWakeRequest wake(UUID householdId, UUID ownerId, String focus) {
        ObjectNode payload = json.createObjectNode();
        if (ownerId != null) {
            payload.put("ownerId", ownerId.toString());
        }
        if (focus != null) {
            payload.put("focus", focus);
        }
        return new AgentWakeRequest(UUID.randomUUID(), householdId, "coordinator", "coordinator.surface", payload);
    }

    private static MemoryDto memoryOf(UUID householdId, UUID userId, String text) {
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, "note", text, null, Instant.now());
    }

    private static String llmJson(String content) {
        return "{\"model\":\"mock-large\",\"content\":" + quote(content)
                + ",\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":100,\"completionTokens\":40,\"totalTokens\":140}}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Dispatcher fixedJson(String body) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return jsonResponse(body);
            }
        };
    }

    private static Dispatcher ok() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(200);
            }
        };
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
