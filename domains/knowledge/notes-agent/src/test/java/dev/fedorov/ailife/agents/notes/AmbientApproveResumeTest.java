package dev.fedorov.ailife.agents.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
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
 * AC-4 resume side: the "заметил: … — записать?" approval resolved through {@code POST /agents/notes/resume}.
 * memory-service stashed a ready-to-write note in the {@code pendingAction}; an affirmative reply writes it
 * ({@code source=ambient}), anything else drops it. MockWebServer stands in for memory-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AmbientApproveResumeTest {

    static MockWebServer memoryService;

    @BeforeAll
    static void start() throws Exception {
        memoryService = new MockWebServer();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("notes-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:1");   // unused on this path
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    private final UUID household = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID mama = UUID.randomUUID();

    /** The ready-to-write note memory-service stashes, wrapped in the ambient-approve pendingAction. */
    private JsonNode pendingAction() {
        WriteNoteRequest note = new WriteNoteRequest(household, owner, "Мама — аллергия", "person",
                null, "ambient", mama, "аллергия на орехи\n[[Мама]]", null);
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "ambient-approve");
        pending.set("note", json.valueToTree(note));
        return pending;
    }

    private NormalizedMessage reply(String text) {
        return new NormalizedMessage(owner, household, MessageScope.PRIVATE, text, List.of(),
                "telegram", "r1", Instant.now());
    }

    private IntentResponse resume(NormalizedMessage msg, JsonNode pending) {
        return http.post().uri("/agents/notes/resume")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new ResumeRequest(msg, pending))
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    @Test
    void affirmativeReply_writesTheAmbientNote() throws Exception {
        memoryService.enqueue(jsonResponse(json.writeValueAsString(new NoteDto(
                UUID.randomUUID(), household, owner, "Мама — аллергия", "person", List.of(),
                "ambient", mama, "аллергия на орехи\n[[Мама]]", null, Instant.now(), Instant.now()))));

        IntentResponse resp = resume(reply("да"), pendingAction());
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Записал").contains("Мама — аллергия");
        assertThat(resp.pendingAction()).isNull();   // lock cleared

        RecordedRequest saveReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(saveReq.getPath()).isEqualTo("/v1/notes");
        JsonNode body = json.readTree(saveReq.getBody().readUtf8());
        assertThat(body.path("source").asText()).isEqualTo("ambient");
        assertThat(body.path("personId").asText()).isEqualTo(mama.toString());
        assertThat(body.path("ownerId").asText()).isEqualTo(owner.toString());
        assertThat(body.path("bodyMd").asText()).contains("[[Мама]]");
    }

    @Test
    void negativeReply_dropsTheNote() {
        int before = memoryService.getRequestCount();   // cumulative across the shared server
        IntentResponse resp = resume(reply("нет"), pendingAction());
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("не записываю");
        assertThat(resp.pendingAction()).isNull();
        assertThat(memoryService.getRequestCount()).isEqualTo(before);   // nothing written
    }

    @Test
    void unknownFlow_repliesGracefully() {
        int before = memoryService.getRequestCount();
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "something-else");

        IntentResponse resp = resume(reply("да"), pending);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");
        assertThat(memoryService.getRequestCount()).isEqualTo(before);
    }

    @Test
    void missingNote_repliesGracefully() {
        int before = memoryService.getRequestCount();
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "ambient-approve");   // flow present, no note payload

        IntentResponse resp = resume(reply("да"), pending);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Нечего подтверждать");
        assertThat(memoryService.getRequestCount()).isEqualTo(before);
    }
}
