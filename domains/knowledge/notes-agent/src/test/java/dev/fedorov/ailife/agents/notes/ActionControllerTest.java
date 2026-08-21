package dev.fedorov.ailife.agents.notes;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reserved {@code undo} inter-agent action (road-test #486, Track H — notes rollout): the orchestrator
 * dispatches a just-captured note's stored handle here, and it reverses the capture by deleting the note via
 * memory-service's {@code DELETE /v1/notes/{id}}. A MockWebServer stands in for memory-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActionControllerTest {

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
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @BeforeEach
    void drain() throws Exception {
        while (memoryService.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard leftovers so each test's takeRequest() sees only its own call
        }
    }

    @Test
    void undoDeletesTheNoteAndConfirms() throws Exception {
        UUID noteId = UUID.randomUUID();
        memoryService.enqueue(new MockResponse().setResponseCode(204));  // DELETE /v1/notes/{id}

        ObjectNode args = json.createObjectNode();
        args.put("noteId", noteId.toString());
        args.put("title", "Мама — что любит");
        var req = new AgentActionRequest("notes", "undo", UUID.randomUUID(), null, "orchestrator", args);

        http.post().uri("/agents/notes/actions/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("message").asString()).contains("Мама — что любит");
                });

        RecordedRequest sent = memoryService.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("DELETE");
        assertThat(sent.getPath()).isEqualTo("/v1/notes/" + noteId);
    }

    @Test
    void undoOfAnAlreadyGoneNoteIsHonest() throws Exception {
        UUID noteId = UUID.randomUUID();
        memoryService.enqueue(new MockResponse().setResponseCode(404));  // note already deleted

        ObjectNode args = json.createObjectNode();
        args.put("noteId", noteId.toString());
        var req = new AgentActionRequest("notes", "undo", UUID.randomUUID(), null, "orchestrator", args);

        http.post().uri("/agents/notes/actions/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("заметку");
                });
    }

    @Test
    void undoWithoutNoteIdIsError() {
        var req = new AgentActionRequest("notes", "undo", UUID.randomUUID(), null, "orchestrator", null);
        http.post().uri("/agents/notes/actions/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("noteId");
                });
    }

    @Test
    void unknownActionReturnsErrorResult() {
        var req = new AgentActionRequest("notes", "frobnicate", UUID.randomUUID(), null, "orchestrator", null);
        http.post().uri("/agents/notes/actions/frobnicate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("unknown action");
                });
    }
}
