package dev.fedorov.ailife.agents.notes;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.note.NoteDto;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the memory-review digest flow (MQ-1, road-test #488) through the agent's HTTP surface
 * ({@code POST /agents/notes/intent}): a "что ты про меня запомнил" cue → the router picks
 * {@code memory-review} → the reviewer gathers notes ({@code GET /v1/notes}) + facts
 * ({@code GET /v1/memories}) from memory-service and folds them into one readable digest.
 * MockWebServers stand in for llm-gateway (routing turn only) and memory-service; the memory-service
 * responses are keyed by path because the reviewer fetches both tiers concurrently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MemoryReviewerTest {

    static MockWebServer memoryService;
    static MockWebServer llmGateway;
    static volatile String notesJson = "[]";
    static volatile String factsJson = "[]";

    @BeforeAll
    static void start() throws Exception {
        memoryService = new MockWebServer();
        llmGateway = new MockWebServer();
        memoryService.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath() == null ? "" : req.getPath();
                if (path.startsWith("/v1/notes")) {
                    return jsonResponse(notesJson);
                }
                if (path.startsWith("/v1/memories")) {
                    return jsonResponse(factsJson);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        memoryService.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        memoryService.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("notes-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void reviewCueListsNotesAndFactsExcludingListsAndNoteSeeds() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        notesJson = json.writeValueAsString(List.of(
                note(householdId, userId, "Мама — что любит", "person", "Любит пионы в горшке, не срезку"),
                note(householdId, userId, "список покупок", "list", "- [ ] молоко\n- [ ] хлеб")));  // excluded
        factsJson = json.writeValueAsString(List.of(
                fact(householdId, userId, "chat-capture", "У Маши аллергия на орехи."),
                fact(householdId, userId, "note", "Мама — что любит Любит пионы")));  // note seed, excluded

        enqueueRouting("memory-review");
        IntentResponse resp = post(new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что ты про меня запомнил?", List.of(), "telegram", "20", Instant.now()));

        assertThat(resp).isNotNull();
        assertThat(resp.text())
                .contains("Мама — что любит")
                .contains("Любит пионы")
                .contains("У Маши аллергия на орехи")
                .contains("удали заметку про")
                .contains("забудь, что");
        // The list-type note and the note-seed fact are not shown as remembered facts.
        assertThat(resp.text()).doesNotContain("список покупок");
        assertThat(resp.text()).doesNotContain("Мама — что любит Любит пионы");  // the seed text form
    }

    @Test
    void nothingRememberedRepliesEmpty() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        notesJson = "[]";
        factsJson = "[]";

        enqueueRouting("memory-review");
        IntentResponse resp = post(new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что ты про меня знаешь?", List.of(), "telegram", "21", Instant.now()));

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("ничего не запомнил");
    }

    private NoteDto note(UUID householdId, UUID userId, String title, String type, String body) {
        return new NoteDto(UUID.randomUUID(), householdId, userId, title, type, List.of(),
                "user", null, body, null, Instant.now(), Instant.now());
    }

    private MemoryDto fact(UUID householdId, UUID userId, String source, String text) {
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, source, text, null, Instant.now());
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/notes/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private void enqueueRouting(String skill) {
        llmGateway.enqueue(jsonResponse(safe(new LlmChatResponse(
                "mock-large", "{\"action\":\"skill\",\"name\":\"" + skill + "\"}",
                "stop", new LlmUsage(10, 4, 14)))));
    }

    private String safe(LlmChatResponse r) {
        return json.writeValueAsString(r);
    }
}
