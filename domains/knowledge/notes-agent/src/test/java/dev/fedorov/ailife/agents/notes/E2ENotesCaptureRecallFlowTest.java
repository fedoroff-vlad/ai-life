package dev.fedorov.ailife.agents.notes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.note.NoteBacklinksResponse;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SB-4 end-to-end closer for the notes-agent (epic #257). Proves the full capture→recall chain over
 * <b>real HTTP boundaries</b> in ONE real notes-agent Spring context, with MockWebServers standing in
 * for every hop (llm-gateway, memory-service), asserting the {@code libs/contracts} DTOs survive
 * serialisation each way:
 *
 * <ol>
 *   <li><b>Capture</b> — a "запомни …" message → LLM note structure → {@code WriteNoteRequest} stored at
 *       {@code POST /v1/notes}, returning a {@code NoteDto} with a stable id.</li>
 *   <li><b>Recall</b> — a "что я думал про …" cue → LLM query distil → memory-service recall
 *       ({@code RecallMemoryHit}, {@code source=note}, {@code refId}) → the {@code refId} resolves to
 *       {@code GET /v1/notes/{id}} → the reply lists the same note captured in step 1, enriched with its
 *       backlinks.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class E2ENotesCaptureRecallFlowTest {

    static MockWebServer llmGateway;
    static MockWebServer memoryService;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        memoryService = new MockWebServer();
        llmGateway.start();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("notes-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void capturedNoteIsRecoveredBySemanticRecall() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();          // threaded capture → recall → getNote
        String title = "Мама — что любит";
        String body = "Любит пионы в горшке, не срезку. [[Мама]]";

        // ---- Phase 1: capture ------------------------------------------------------------------
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"title\":\"" + title + "\",\"type\":\"person\",\"tags\":[\"подарок\"],\"body\":\"" + body + "\"}",
                "stop", new LlmUsage(40, 20, 60)))));
        NoteDto stored = new NoteDto(noteId, householdId, userId, title, "person", List.of("подарок"),
                "user", null, body, null, Instant.now(), Instant.now());
        memoryService.enqueue(jsonResponse(json.writeValueAsString(stored)));

        NormalizedMessage capture = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "запомни, что мама любит пионы в горшке, не срезку", List.of(), "telegram", "e2e-1", Instant.now());

        IntentResponse captureResp = post(capture);
        assertThat(captureResp).isNotNull();
        assertThat(captureResp.text()).contains("Запомнил").contains(title);

        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        RecordedRequest saveReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(saveReq.getPath()).isEqualTo("/v1/notes");
        JsonNode saveBody = json.readTree(saveReq.getBody().readUtf8());
        assertThat(saveBody.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(saveBody.path("title").asText()).isEqualTo(title);
        assertThat(saveBody.path("source").asText()).isEqualTo("user");

        // ---- Phase 2: recall — the note is recovered via the semantic path ---------------------
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"подарок маме\"}", "stop", new LlmUsage(20, 8, 28)))));
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new RecallMemoryHit(noteMemory(householdId, userId, noteId, title + "\n\n" + body), 0.12)))));
        memoryService.enqueue(jsonResponse(json.writeValueAsString(stored)));                 // refId → getNote
        memoryService.enqueue(jsonResponse(json.writeValueAsString(
                new NoteBacklinksResponse(noteId, List.of()))));                               // top-hit backlinks

        NormalizedMessage recall = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что я думал про подарок маме?", List.of(), "telegram", "e2e-2", Instant.now());

        IntentResponse recallResp = post(recall);
        assertThat(recallResp).isNotNull();
        assertThat(recallResp.text()).contains(title).contains("Любит пионы");

        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        RecordedRequest recallReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq.getPath()).isEqualTo("/v1/memories/recall");
        assertThat(recallReq.getBody().readUtf8()).contains("подарок маме");
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/notes/" + noteId);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/v1/notes/" + noteId + "/backlinks");
    }

    private MemoryDto noteMemory(UUID householdId, UUID userId, UUID noteId, String text) {
        ObjectNode meta = json.createObjectNode();
        meta.put("kind", "note");
        meta.put("refId", noteId.toString());
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, "note", text, meta, Instant.now());
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
