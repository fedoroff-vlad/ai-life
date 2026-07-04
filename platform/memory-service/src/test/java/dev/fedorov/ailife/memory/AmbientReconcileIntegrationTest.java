package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteReconciler;
import dev.fedorov.ailife.memory.capture.NoteReconciliation;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.ReconcileAction;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AC-5b — reconcile-on-write across the real {@code POST /v1/capture} boundary into Postgres. A second
 * explicit-fixation about the same thing must <b>enrich the existing note</b>, not create a duplicate: the
 * near-duplicate is found by recall, resolved to its row, and {@code NoteService.update}d with the merged
 * body. The {@link NoteReconciler} (its own LLM decision, unit/golden-tested) is mocked to a deterministic
 * ENRICH; persistence, embedding and the dedup lookup are real. A <b>constant</b> embedder guarantees the
 * two mentions collide (a hash embedder is not semantic), isolating the reconcile wiring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"event-bus.enabled=false", "memory.ambient-capture.enabled=true"})
class AmbientReconcileIntegrationTest extends AbstractPostgresIntegrationTest {

    static MockWebServer llmGateway;

    /** Every input embeds to the same vector, so recall always finds the stored note (distance 0). */
    private static float[] constantEmbedding() {
        float[] v = new float[384];
        java.util.Arrays.fill(v, 0.1f);
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
                    LlmEmbedResponse body = new LlmEmbedResponse(
                            "mock-embed", List.of(constantEmbedding()), new LlmUsage(0, 0, 0));
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

    @MockBean FactExtractor facts;
    @MockBean RelationExtractor relationExtractor;
    @MockBean NoteWorthinessExtractor noteExtractor;
    @MockBean ProfileClient profile;
    @MockBean NoteReconciler reconciler;   // its own decision is unit/golden-tested; here we pin ENRICH

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    UUID household;
    UUID owner;

    @BeforeAll
    static void applySchemaOnce() {
        applySchema("test-schema.sql");
    }

    @BeforeEach
    void seed() {
        household = UUID.randomUUID();
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "reconcile");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private void capture(NoteCandidate candidate) {
        when(noteExtractor.extract(any())).thenReturn(List.of(candidate));
        client().post().uri("/v1/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CaptureRequest(household, owner, null, "msg"))
                .exchange().expectStatus().isOk();
    }

    private static NoteCandidate explicit(String title, String body) {
        return new NoteCandidate(title, "fact", body, "self", "important", true);
    }

    @Test
    void secondMentionWithANewDetail_enrichesInsteadOfDuplicating() {
        // First mention → a new note is created and seeded for recall.
        capture(explicit("Планы на отпуск", "хочу в горы"));
        assertThat(notesInHousehold()).hasSize(1);

        // Second mention adds a detail; the reconciler says ENRICH with the merged body.
        when(reconciler.reconcile(any(), any(), any(), any()))
                .thenReturn(new NoteReconciliation(ReconcileAction.ENRICH, "хочу в горы, в июле, с палаткой"));
        capture(explicit("Планы на отпуск", "в июле, с палаткой"));

        // Still exactly one note — enriched in place, not duplicated.
        List<Map<String, Object>> notes = notesInHousehold();
        assertThat(notes).hasSize(1);
        assertThat((String) notes.get(0).get("body_md")).isEqualTo("хочу в горы, в июле, с палаткой");
    }

    private List<Map<String, Object>> notesInHousehold() {
        return jdbc.queryForList("SELECT * FROM memory.note WHERE household_id = ?", household);
    }
}
