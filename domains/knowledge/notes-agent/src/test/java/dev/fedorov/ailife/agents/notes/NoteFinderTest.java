package dev.fedorov.ailife.agents.notes;

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
 * Exercises the note-finder recall flow (SB-4) through the agent's HTTP surface ({@code POST
 * /agents/notes/intent}): a "что я думал про …" cue → llm-gateway distils a query via the
 * {@code note-finder} SKILL → memory-service recall surfaces a note hit → its {@code refId} resolves to
 * the note row and the top hit's backlinks are appended. MockWebServers stand in for llm-gateway and
 * memory-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class NoteFinderTest {

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
    void recallCueListsNotesWithConnectedBacklinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID linkedId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"подарок маме\"}", "stop", new LlmUsage(20, 8, 28)))));
        // recall → one note-scoped hit carrying the refId back-pointer
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new RecallMemoryHit(noteMemory(householdId, userId, noteId, "Мама — что любит\n\nЛюбит пионы"), 0.1)))));
        // refId → GET /v1/notes/{id}
        memoryService.enqueue(jsonResponse(json.writeValueAsString(new NoteDto(
                noteId, householdId, userId, "Мама — что любит", "person", List.of("подарок"),
                "user", null, "Любит пионы в горшке", null, Instant.now(), Instant.now()))));
        // top hit backlinks → the connected note
        memoryService.enqueue(jsonResponse(json.writeValueAsString(new NoteBacklinksResponse(noteId, List.of(
                new NoteDto(linkedId, householdId, userId, "Мама", "person", List.of(),
                        "user", null, "профиль мамы", null, Instant.now(), Instant.now()))))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что я думал про подарок маме?", List.of(), "telegram", "10", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text())
                .contains("Мама — что любит")
                .contains("Любит пионы")
                .contains("Связано")
                .contains("Мама");

        // The distil went through llm-gateway with the SKILL system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");

        // memory-service saw: recall → get note → backlinks, in that order.
        RecordedRequest recallReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq.getPath()).isEqualTo("/v1/memories/recall");
        assertThat(recallReq.getBody().readUtf8()).contains("подарок маме");
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/notes/" + noteId);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/v1/notes/" + noteId + "/backlinks");
    }

    @Test
    void noHitsRepliesNothingFound() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"страховка\"}", "stop", new LlmUsage(12, 5, 17)))));
        memoryService.enqueue(jsonResponse("[]"));   // recall finds nothing

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что я думал про страховку?", List.of(), "telegram", "11", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Ничего не нашёл");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/memories/recall");
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
