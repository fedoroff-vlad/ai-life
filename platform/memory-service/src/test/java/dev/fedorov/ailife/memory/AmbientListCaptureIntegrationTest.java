package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.ListIntentExtractor;
import dev.fedorov.ailife.memory.capture.ListItemCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.http.NotifierClient;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * LI-b2 stage closer — ambient list capture across the real {@code POST /v1/capture} HTTP boundary into a
 * real Postgres. The LLM decision ({@link ListIntentExtractor}) is mocked so the item is deterministic;
 * everything downstream is real: {@code CaptureService} finds-or-creates the {@code type=list}
 * {@code memory.note} and appends via {@code MarkdownChecklist}. The notifier ack is mocked (a side push).
 *
 * <p>Proves: a buy-intent with no keyword → a household-shared {@code type=list} note holding the item; a
 * second item → appended to the same note; the same item twice → exactly one checklist entry (idempotent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"event-bus.enabled=false", "memory.ambient-capture.enabled=true"})
@AutoConfigureWebTestClient
class AmbientListCaptureIntegrationTest extends AbstractPostgresIntegrationTest {

    static MockWebServer llmGateway;

    /** Deterministic mock embedder keyed on the text — NoteService seeds recall on every list write. */
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
                    String input = node.get("inputs").get(0).asString();
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
        registry.add("memory.profile-base-url", () -> "http://127.0.0.1:1");
    }

    @AfterAll
    static void stop() throws IOException {
        if (llmGateway != null) llmGateway.shutdown();
    }

    // The LLM decision + the notifier push are mocked; persistence + embedding are real.
    @MockitoBean FactExtractor facts;
    @MockitoBean RelationExtractor relationExtractor;
    @MockitoBean NoteWorthinessExtractor noteExtractor;
    @MockitoBean ListIntentExtractor listExtractor;
    @MockitoBean ProfileClient profile;
    @MockitoBean NotifierClient notifier;

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
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "lists");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private void capture(String text) {
        client().post().uri("/v1/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CaptureRequest(household, owner, null, text))
                .exchange().expectStatus().isOk()
                .expectBodyList(MemoryDto.class);
    }

    private List<Map<String, Object>> listNotes() {
        return jdbc.queryForList(
                "SELECT * FROM memory.note WHERE household_id = ? AND type = 'list'", household);
    }

    @Test
    void buyIntent_absentList_createsHouseholdSharedListNote() {
        when(listExtractor.extract(any()))
                .thenReturn(List.of(new ListItemCandidate("молоко", "список покупок")));

        capture("надо купить молоко");

        List<Map<String, Object>> notes = listNotes();
        assertThat(notes).hasSize(1);
        Map<String, Object> note = notes.get(0);
        assertThat(note.get("title")).isEqualTo("список покупок");
        assertThat(note.get("owner_id")).isNull();          // household-shared
        assertThat(note.get("source")).isEqualTo("ambient");
        assertThat((String) note.get("body_md")).isEqualTo("- [ ] молоко");
    }

    @Test
    void secondItem_appendsToTheSameList() {
        when(listExtractor.extract(any()))
                .thenReturn(List.of(new ListItemCandidate("молоко", "список покупок")));
        capture("надо купить молоко");

        when(listExtractor.extract(any()))
                .thenReturn(List.of(new ListItemCandidate("хлеб", "список покупок")));
        capture("ещё нужен хлеб");

        List<Map<String, Object>> notes = listNotes();
        assertThat(notes).hasSize(1);
        assertThat((String) notes.get(0).get("body_md")).isEqualTo("- [ ] молоко\n- [ ] хлеб");
    }

    @Test
    void sameItemTwice_staysOneEntry() {
        when(listExtractor.extract(any()))
                .thenReturn(List.of(new ListItemCandidate("молоко", "список покупок")));

        capture("надо купить молоко");
        capture("не забыть купить молоко");   // idempotent add — no duplicate line

        List<Map<String, Object>> notes = listNotes();
        assertThat(notes).hasSize(1);
        assertThat((String) notes.get(0).get("body_md")).isEqualTo("- [ ] молоко");
    }
}
