package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.http.ProfileClient;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Ambient-capture stage closer — the AC-2/AC-3 chain across the real {@code POST /v1/capture} HTTP
 * boundary into a real Postgres. The LLM decision (the three extractors) and person resolution
 * ({@link ProfileClient}) are mocked so the note candidates and attribution are deterministic; everything
 * downstream is real: {@code CaptureService → NoteService} writes a {@code memory.note} row, seeds its
 * recall memory, and projects its {@code [[wiki-link]]} into {@code memory.relations}, and the AC-3 dedup
 * recall runs against the real pgvector store.
 *
 * <p>Proves: an explicit-fixation about a person → an attributed note (+ note→person edge); a "self"
 * fixation → an owner-scoped note; the same message twice → exactly one note (dedup).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"event-bus.enabled=false", "memory.ambient-capture.enabled=true"})
class AmbientCaptureIntegrationTest extends AbstractPostgresIntegrationTest {

    static MockWebServer llmGateway;

    /** Deterministic mock embedder keyed on the text, so identical corpora collide (dedup) and recall works. */
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
        // ProfileClient is mocked below; the base URL just needs to be set to something.
        registry.add("memory.profile-base-url", () -> "http://127.0.0.1:1");
    }

    @AfterAll
    static void stop() throws IOException {
        if (llmGateway != null) llmGateway.shutdown();
    }

    // The LLM decision + person resolution are mocked; persistence, embedding and dedup are real.
    @MockBean FactExtractor facts;                 // returns [] by default — no free-text facts in these cases
    @MockBean RelationExtractor relationExtractor; // returns [] by default — focus stays on the note output
    @MockBean NoteWorthinessExtractor noteExtractor;
    @MockBean ProfileClient profile;

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    UUID household;
    UUID owner;
    UUID mama;

    @BeforeAll
    static void applySchemaOnce() {
        applySchema("test-schema.sql");
    }

    @BeforeEach
    void seed() {
        household = UUID.randomUUID();
        owner = UUID.randomUUID();
        mama = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "ambient");
        when(profile.resolvePersonId(household, "Мама")).thenReturn(mama);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static NoteCandidate explicit(String title, String type, String body, String subject) {
        return new NoteCandidate(title, type, body, subject, "important", true);
    }

    /** Fire a real capture over HTTP; returns the (facts-only) response body. */
    private List<MemoryDto> capture(String text) {
        return client().post().uri("/v1/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CaptureRequest(household, owner, null, text))
                .exchange().expectStatus().isOk()
                .expectBodyList(MemoryDto.class).returnResult().getResponseBody();
    }

    private List<Map<String, Object>> notesInHousehold() {
        return jdbc.queryForList("SELECT * FROM memory.note WHERE household_id = ?", household);
    }

    @Test
    void explicitFixationAboutPerson_writesAttributedNoteWithPersonEdge() {
        when(noteExtractor.extract(any()))
                .thenReturn(List.of(explicit("Мама — аллергия", "person", "аллергия на орехи", "Мама")));

        capture("запомни: у мамы аллергия на орехи");

        List<Map<String, Object>> notes = notesInHousehold();
        assertThat(notes).hasSize(1);
        Map<String, Object> note = notes.get(0);
        assertThat(note.get("source")).isEqualTo("user");
        assertThat(note.get("type")).isEqualTo("person");
        assertThat(note.get("owner_id")).isEqualTo(owner);
        assertThat(note.get("person_id")).isEqualTo(mama);
        assertThat((String) note.get("body_md")).contains("[[Мама]]");

        // SB-3 note→person edge projected from the [[Мама]] wiki-link.
        UUID noteId = (UUID) note.get("id");
        Integer edges = jdbc.queryForObject("""
                SELECT count(*) FROM memory.relations
                 WHERE household_id = ? AND subject_type = 'note' AND subject_id = ?
                   AND edge = 'links_to' AND object_type = 'person' AND object_id = ?
                """, Integer.class, household, noteId, mama);
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void explicitSelfFixation_writesOwnerScopedNote() {
        when(noteExtractor.extract(any()))
                .thenReturn(List.of(explicit("Сменить работу", "goal", "решил сменить работу", "self")));

        capture("зафиксируй: решил сменить работу");

        List<Map<String, Object>> notes = notesInHousehold();
        assertThat(notes).hasSize(1);
        Map<String, Object> note = notes.get(0);
        assertThat(note.get("source")).isEqualTo("user");
        assertThat(note.get("type")).isEqualTo("goal");
        assertThat(note.get("owner_id")).isEqualTo(owner);   // owner-scoped
        assertThat(note.get("person_id")).isNull();          // not about a person
        assertThat((String) note.get("body_md")).doesNotContain("[[");   // no wiki-link for self
    }

    @Test
    void sameExplicitFixationTwice_dedupsToOneNote() {
        when(noteExtractor.extract(any()))
                .thenReturn(List.of(explicit("Мама — аллергия", "person", "аллергия на орехи", "Мама")));

        capture("запомни: у мамы аллергия на орехи");
        capture("напоминаю, у мамы аллергия на орехи");   // same candidate → same title+body corpus

        // AC-3: the near-identical second note is skipped on write.
        assertThat(notesInHousehold()).hasSize(1);
    }
}
