package dev.fedorov.ailife.agents.coach;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.coach.CoachProfileDto;
import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.note.NoteDto;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CO-2 end-to-end closer for the coach-agent (#289). Proves the full safety → gather → synthesize →
 * persist chain over <b>real HTTP boundaries</b> in ONE real coach-agent Spring context, with
 * MockWebServers standing in for every hop (llm-gateway, memory-service, mcp-coach), asserting the
 * {@code libs/contracts} DTOs survive serialisation each way:
 *
 * <ol>
 *   <li><b>Reflect</b> — a reflective message → safety check (not a crisis) → gather (the subject's own
 *       notes + memory recall + prior sessions; another member's journal note must NOT reach the
 *       synthesis — the Decision-0 privacy boundary) → strict-JSON synthesis → a {@code coach_session}
 *       + observations (unknown method dropped) + hypotheses (confidence clamped) persisted at
 *       mcp-coach {@code /internal/coach/*} → the JSON's reply reaches the user.</li>
 *   <li><b>Crisis</b> — a crisis signal short-circuits everything: care + referral reply, no gather, no
 *       synthesis, no store writes.</li>
 *   <li><b>Prose degradation</b> — a model that answers in prose instead of JSON still reaches the user;
 *       nothing is persisted.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class E2ECoachReflectFlowTest {

    static MockWebServer llmGateway;
    static MockWebServer memoryService;
    static MockWebServer mcpCoach;

    static final UUID HOUSEHOLD = UUID.randomUUID();
    static final UUID SUBJECT = UUID.randomUUID();
    static final UUID OTHER_MEMBER = UUID.randomUUID();
    static final UUID SESSION_ID = UUID.randomUUID();

    static final String SUBJECT_NOTE_TITLE = "Опять забросил проект";
    static final String OTHER_NOTE_TITLE = "Чужой дневник — не для коуча";

    static volatile ObjectMapper staticJson;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        memoryService = new MockWebServer();
        mcpCoach = new MockWebServer();
        llmGateway.start();
        memoryService.start();
        mcpCoach.start();
        memoryService.setDispatcher(memoryDispatcher());
        mcpCoach.setDispatcher(coachStoreDispatcher());
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        memoryService.shutdown();
        mcpCoach.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("coach-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("coach-agent.mcp-coach-url", () -> "http://localhost:" + mcpCoach.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @BeforeEach
    void drainRecordedRequests() throws Exception {
        staticJson = json;
        while (llmGateway.takeRequest(50, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        while (memoryService.takeRequest(50, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        while (mcpCoach.takeRequest(50, TimeUnit.MILLISECONDS) != null) { /* drain */ }
    }

    @Test
    void reflectGathersSubjectMaterialAndPersistsTheRecord() throws Exception {
        llmGateway.enqueue(jsonResponse("{\"model\":\"mock-fast\",\"content\":\"{\\\"crisis\\\": false}\",\"finishReason\":\"stop\"}"));
        ObjectNode reflectOut = json.createObjectNode();
        reflectOut.put("reply", "Похоже, новые проекты появляются в моменты тревоги. Что для вас в них главное?");
        reflectOut.put("sessionSummary", "Looked at the abandoned-projects pattern.");
        ArrayNode observations = reflectOut.putArray("observations");
        observations.addObject()
                .put("text", "Starts a new project within days of an anxiety spike.")
                .put("method", "cbt");
        observations.addObject()
                .put("text", "Should be dropped — not an evidence-based move.")
                .put("method", "gestalt");
        reflectOut.putArray("hypotheses").addObject()
                .put("text", "New projects may serve as escape from financial anxiety.")
                .put("confidence", 130);
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", json.writeValueAsString(reflectOut), "stop", new LlmUsage(200, 120, 320)))));

        IntentResponse resp = post(message("Помоги разобраться, почему я опять всё бросил на полпути"));

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("coach");
        assertThat(resp.text()).contains("Похоже, новые проекты появляются");
        assertThat(resp.llmModel()).isEqualTo("mock-large");

        // Two LLM hops: the FAST safety check, then the synthesis over the gathered context.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        RecordedRequest synthesis = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(synthesis.getPath()).isEqualTo("/v1/chat");
        String synthesisBody = synthesis.getBody().readUtf8();
        assertThat(synthesisBody).contains(SUBJECT_NOTE_TITLE);
        // Decision 0: another member's journal note never enters the subject's session.
        assertThat(synthesisBody).doesNotContain(OTHER_NOTE_TITLE);

        // memory-service: the notes read + the semantic recall (parallel gather, order not fixed).
        RecordedRequest m1 = memoryService.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest m2 = memoryService.takeRequest(2, TimeUnit.SECONDS);
        List<String> memoryPaths = List.of(m1.getPath(), m2.getPath());
        assertThat(memoryPaths).anyMatch(p -> p.startsWith("/v1/notes"));
        assertThat(memoryPaths).anyMatch(p -> p.startsWith("/v1/memories/recall"));

        // mcp-coach: profile + prior sessions reads, then session → observation(s) → hypothesis writes.
        assertThat(mcpCoach.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/internal/coach/profile");
        assertThat(mcpCoach.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/internal/coach/sessions?");

        RecordedRequest sessionWrite = mcpCoach.takeRequest(2, TimeUnit.SECONDS);
        assertThat(sessionWrite.getPath()).isEqualTo("/internal/coach/sessions");
        JsonNode sessionBody = json.readTree(sessionWrite.getBody().readUtf8());
        assertThat(sessionBody.path("householdId").asText()).isEqualTo(HOUSEHOLD.toString());
        assertThat(sessionBody.path("subject").asText()).isEqualTo(SUBJECT.toString());
        assertThat(sessionBody.path("mode").asText()).isEqualTo("reflect");

        RecordedRequest observationWrite = mcpCoach.takeRequest(2, TimeUnit.SECONDS);
        assertThat(observationWrite.getPath()).isEqualTo("/internal/coach/observations");
        JsonNode observationBody = json.readTree(observationWrite.getBody().readUtf8());
        assertThat(observationBody.path("method").asText()).isEqualTo("cbt");
        assertThat(observationBody.path("sessionId").asText()).isEqualTo(SESSION_ID.toString());

        // The "gestalt" observation was dropped — the next write is already the hypothesis.
        RecordedRequest hypothesisWrite = mcpCoach.takeRequest(2, TimeUnit.SECONDS);
        assertThat(hypothesisWrite.getPath()).isEqualTo("/internal/coach/hypotheses");
        JsonNode hypothesisBody = json.readTree(hypothesisWrite.getBody().readUtf8());
        assertThat(hypothesisBody.path("confidence").asInt()).isEqualTo(100);   // clamped from 130
        assertThat(mcpCoach.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void crisisSignalShortCircuitsToReferralWithoutTouchingTheStore() throws Exception {
        llmGateway.enqueue(jsonResponse("{\"model\":\"mock-fast\",\"content\":\"{\\\"crisis\\\": true}\",\"finishReason\":\"stop\"}"));

        IntentResponse resp = post(message("Я больше так не могу, не вижу смысла продолжать"));

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("112");
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        // The coaching frame was dropped: no second LLM call, no gather, no store access at all.
        assertThat(llmGateway.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mcpCoach.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(memoryService.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void proseSynthesisStillReachesTheUserButPersistsNothing() throws Exception {
        llmGateway.enqueue(jsonResponse("{\"model\":\"mock-fast\",\"content\":\"{\\\"crisis\\\": false}\",\"finishReason\":\"stop\"}"));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Похоже, вы давно не давали себе отдыха. Что помогало раньше?",
                "stop", new LlmUsage(100, 40, 140)))));

        IntentResponse resp = post(message("Почему я всё время чувствую себя виноватым за отдых?"));

        assertThat(resp).isNotNull();
        assertThat(resp.text()).isEqualTo("Похоже, вы давно не давали себе отдыха. Что помогало раньше?");

        // Store saw only the two reads (profile + prior sessions) — no session/observation writes.
        assertThat(mcpCoach.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/internal/coach/profile");
        assertThat(mcpCoach.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/internal/coach/sessions?");
        assertThat(mcpCoach.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    // ---------- fixtures ----------

    private static NormalizedMessage message(String text) {
        return new NormalizedMessage(SUBJECT, HOUSEHOLD, MessageScope.PRIVATE, text,
                List.of(), "telegram", "e2e-coach", Instant.now());
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/coach/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    /** memory-service stand-in: the household notes list + the semantic recall. */
    private static Dispatcher memoryDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                ObjectMapper j = staticJson;
                String path = request.getPath();
                if (path != null && path.startsWith("/v1/notes")) {
                    return jsonResponse(j.writeValueAsString(List.of(
                            note(SUBJECT, SUBJECT_NOTE_TITLE, "journal",
                                    "Начал третий проект за месяц, первые два заброшены."),
                            note(OTHER_MEMBER, OTHER_NOTE_TITLE, "journal", "Личное чужое."),
                            note(SUBJECT, "Рецепт борща", "fact", "Не рефлексия — не для сессии."))));
                }
                if (path != null && path.startsWith("/v1/memories/recall")) {
                    return jsonResponse(j.writeValueAsString(List.of(new RecallMemoryHit(
                            new MemoryDto(UUID.randomUUID(), HOUSEHOLD, SUBJECT, null, "chat-capture",
                                    "Тревожится из-за денег при стабильном доходе.", null, Instant.now()),
                            0.2))));
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    /** mcp-coach stand-in: profile/sessions reads + session/observation/hypothesis writes. */
    private static Dispatcher coachStoreDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                ObjectMapper j = staticJson;
                String path = request.getPath() == null ? "" : request.getPath();
                boolean post = "POST".equals(request.getMethod());
                if (path.startsWith("/internal/coach/profile")) {
                    return jsonResponse(j.writeValueAsString(new CoachProfileDto(
                            UUID.randomUUID(), HOUSEHOLD, SUBJECT,
                            j.readTree("{\"mi\": 0.5, \"cbt\": 0.3}"), "warm, plain",
                            null, null, true, Instant.now())));
                }
                if (path.startsWith("/internal/coach/sessions") && !post) {
                    return jsonResponse(j.writeValueAsString(List.of(new CoachSessionDto(
                            UUID.randomUUID(), HOUSEHOLD, SUBJECT, "reflect",
                            "Prior session about procrastination.", Instant.now()))));
                }
                if (path.equals("/internal/coach/sessions")) {
                    return jsonResponse(j.writeValueAsString(new CoachSessionDto(
                            SESSION_ID, HOUSEHOLD, SUBJECT, "reflect", null, Instant.now())));
                }
                if (path.equals("/internal/coach/observations") || path.equals("/internal/coach/hypotheses")) {
                    return jsonResponse("{}");
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static NoteDto note(UUID owner, String title, String type, String body) {
        return new NoteDto(UUID.randomUUID(), HOUSEHOLD, owner, title, type, List.of(), "user",
                null, body, null, Instant.now(), Instant.now());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
