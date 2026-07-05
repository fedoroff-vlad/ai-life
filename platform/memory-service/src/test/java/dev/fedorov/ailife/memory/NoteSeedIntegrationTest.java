package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SB-2 — the note recall seed. Verifies that writing a note embeds its body into
 * {@code memory.memories} ({@code source=note}, {@code metadata.refId}) so the note
 * is recallable through {@code /v1/memories/recall}; that an update re-seeds (one
 * memory per note, old dropped); and that deleting the note forgets its seed. Wires
 * a deterministic mock embedder like {@link MemoryServiceIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.enabled=false")
@AutoConfigureWebTestClient
class NoteSeedIntegrationTest extends AbstractPostgresIntegrationTest {

    static MockWebServer llmGateway;

    private static float[] embeddingFor(String text) {
        Random rnd = new Random(text.hashCode());
        float[] v = new float[384];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextFloat() * 2f - 1f;
        }
        return v;
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) throws IOException {
        registerDataSource(registry);
        llmGateway = new MockWebServer();
        llmGateway.setDispatcher(new Dispatcher() {
            private final ObjectMapper json = new ObjectMapper();

            @Override
            public MockResponse dispatch(RecordedRequest req) {
                try {
                    var node = json.readTree(req.getBody().readUtf8());
                    String input = node.get("inputs").get(0).asText();
                    LlmEmbedResponse body = new LlmEmbedResponse(
                            "mock-embed", List.of(embeddingFor(input)), new LlmUsage(0, 0, 0));
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(json.writeValueAsString(body));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(e.toString());
                }
            }
        });
        llmGateway.start();
        registry.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (llmGateway != null) llmGateway.shutdown();
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void applySchemaOnce() {
        applySchema("test-schema.sql");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private UUID freshHousehold() {
        UUID hh = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hh, "sb2");
        return hh;
    }

    private NoteDto createNote(UUID household, String title, String body) {
        return client().post().uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WriteNoteRequest(household, null, title, "fact", null, "user", null, body, null))
                .exchange().expectStatus().isOk()
                .expectBody(NoteDto.class).returnResult().getResponseBody();
    }

    private List<RecallMemoryHit> recall(UUID household, String query) {
        return client().post().uri("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RecallMemoryRequest(household, null, null, query, 5))
                .exchange().expectStatus().isOk()
                .expectBodyList(RecallMemoryHit.class).returnResult().getResponseBody();
    }

    private int noteSeedRows(UUID noteId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM memory.memories WHERE source = 'note' AND metadata->>'refId' = ?",
                Integer.class, noteId.toString());
    }

    @Test
    void writingANoteSeedsARecallableMemory() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "Мама — что любит", "Любит пионы");

        // Exactly one note-memory carries this note's refId back-pointer.
        assertThat(noteSeedRows(note.id())).isEqualTo(1);

        // The seeded corpus (title + body) is recallable; exact query → distance ~0.
        String seed = "Мама — что любит\n\nЛюбит пионы";
        List<RecallMemoryHit> hits = recall(household, seed);
        assertThat(hits).isNotEmpty();
        RecallMemoryHit top = hits.get(0);
        assertThat(top.memory().source()).isEqualTo("note");
        assertThat(top.memory().text()).isEqualTo(seed);
        assertThat(top.memory().metadata().path("refId").asText()).isEqualTo(note.id().toString());
        assertThat(top.memory().metadata().path("kind").asText()).isEqualTo("note");
        assertThat(top.distance()).isLessThan(0.01);
    }

    @Test
    void updatingANoteReseedsExactlyOnce() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "draft", "первый вариант");

        client().put().uri("/v1/notes/{id}", note.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WriteNoteRequest(household, null, "draft", "fact", null, "user", null,
                        "финальный текст", null))
                .exchange().expectStatus().isOk();

        // Still exactly one seed for this note (the old one was dropped, not accumulated).
        assertThat(noteSeedRows(note.id())).isEqualTo(1);

        // The new body is what recall now returns.
        List<RecallMemoryHit> hits = recall(household, "draft\n\nфинальный текст");
        assertThat(hits.get(0).memory().text()).isEqualTo("draft\n\nфинальный текст");
        assertThat(hits.get(0).distance()).isLessThan(0.01);
    }

    @Test
    void deletingANoteForgetsItsSeed() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "temp", "черновик");
        assertThat(noteSeedRows(note.id())).isEqualTo(1);

        client().delete().uri("/v1/notes/{id}", note.id())
                .exchange().expectStatus().isNoContent();

        assertThat(noteSeedRows(note.id())).isZero();
    }
}
