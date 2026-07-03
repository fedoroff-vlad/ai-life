package dev.fedorov.ailife.agents.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.note.NoteDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the note-writer capture flow (SB-4) through the agent's HTTP surface ({@code POST
 * /agents/notes/intent}): a "запомни …" cue → llm-gateway structures a note via the {@code note-writer}
 * SKILL → memory-service stores it at {@code POST /v1/notes} → the reply confirms the title.
 * MockWebServers stand in for llm-gateway and memory-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoteWriterTest {

    static MockWebServer memoryService;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        memoryService = new MockWebServer();
        llmGateway = new MockWebServer();
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
    void captureCueStructuresNoteAndStoresIt() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"title\":\"Мама — что любит\",\"type\":\"person\",\"tags\":[\"подарок\",\"мама\"],"
                        + "\"body\":\"Любит пионы в горшке, не срезку. [[Мама]]\"}",
                "stop", new LlmUsage(40, 20, 60)))));
        UUID noteId = UUID.randomUUID();
        memoryService.enqueue(jsonResponse(json.writeValueAsString(new NoteDto(
                noteId, householdId, userId, "Мама — что любит", "person", List.of("подарок", "мама"),
                "user", null, "Любит пионы в горшке, не срезку. [[Мама]]", null, Instant.now(), Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "запомни, что мама любит пионы в горшке, не срезку", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Запомнил").contains("Мама — что любит");

        // The distil went through llm-gateway with the SKILL system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8()).contains("strict JSON").contains("пионы");

        // The note was stored with the structured fields, scoped to the household + author.
        RecordedRequest saveReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(saveReq.getPath()).isEqualTo("/v1/notes");
        JsonNode body = json.readTree(saveReq.getBody().readUtf8());
        assertThat(body.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(body.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(body.path("title").asText()).isEqualTo("Мама — что любит");
        assertThat(body.path("type").asText()).isEqualTo("person");
        assertThat(body.path("source").asText()).isEqualTo("user");
        assertThat(body.path("bodyMd").asText()).contains("[[Мама]]");
        assertThat(body.path("tags").isArray()).isTrue();
    }

    @Test
    void degradedModelFallsBackToUserWordsForTitle() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Model returns unparseable prose → the writer falls back to the user's own text.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "sorry, I could not do that", "stop", new LlmUsage(5, 5, 10)))));
        memoryService.enqueue(jsonResponse(json.writeValueAsString(new NoteDto(
                UUID.randomUUID(), householdId, userId, "купить лампочки", "fact", List.of(),
                "user", null, "купить лампочки", null, Instant.now(), Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "запиши: купить лампочки", List.of(), "telegram", "2", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Запомнил");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest saveReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(saveReq.getBody().readUtf8());
        // Body falls back to the raw user text; title is derived from it (non-blank).
        assertThat(body.path("bodyMd").asText()).contains("купить лампочки");
        assertThat(body.path("title").asText()).isNotBlank();
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
}
