package dev.fedorov.ailife.memory;

import dev.fedorov.ailife.contracts.note.NoteBacklinksResponse;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SB-3 — the note {@code [[wiki-link]]} graph seed + backlinks. A note's body links
 * project into {@code memory.relations} (subject = the note): resolving to a note (by
 * title), a person ({@code core.people} via profile-service), else a dangling
 * {@code label} edge. {@code GET /v1/notes/{id}/backlinks} reads the reverse.
 *
 * <p>llm-gateway points at a dead address — the SB-2 embedding seed is best-effort so
 * it fails silently, keeping the focus on the graph half (same posture as
 * {@link NotesIntegrationTest}). A mock profile-service resolves {@code [[Мама]]}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.enabled=false")
@AutoConfigureWebTestClient
class NoteLinksIntegrationTest extends AbstractPostgresIntegrationTest {

    static MockWebServer profile;
    static final UUID MAMA_PERSON = UUID.randomUUID();

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) throws IOException {
        registerDataSource(registry);
        registry.add("ailife.llm-client.base-url", () -> "http://127.0.0.1:1"); // embed seed fails, best-effort
        profile = new MockWebServer();
        profile.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest req) {
                // The household only knows one person, "Мама"; every other label is unresolved.
                String body = "[{\"id\":\"" + MAMA_PERSON + "\",\"displayName\":\"Мама\"}]";
                return new MockResponse().setHeader("content-type", "application/json").setBody(body);
            }
        });
        profile.start();
        registry.add("memory.profile-base-url", () -> "http://localhost:" + profile.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (profile != null) profile.shutdown();
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
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hh, "sb3");
        return hh;
    }

    private NoteDto createNote(UUID household, String title, String body) {
        return client().post().uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WriteNoteRequest(household, null, title, "fact", null, "user", null, body, null))
                .exchange().expectStatus().isOk()
                .expectBody(NoteDto.class).returnResult().getResponseBody();
    }

    private List<Map<String, Object>> edgesOf(UUID noteId) {
        return jdbc.queryForList("""
                SELECT edge, object_type, object_id, object_label FROM memory.relations
                 WHERE subject_type = 'note' AND subject_id = ?
                """, noteId);
    }

    @Test
    void linkToExistingNoteCreatesNoteEdgeAndBacklink() {
        UUID household = freshHousehold();
        NoteDto target = createNote(household, "Проект X", "цели проекта");
        NoteDto source = createNote(household, "Заметки", "детали в [[Проект X]]");

        List<Map<String, Object>> edges = edgesOf(source.id());
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0)).containsEntry("edge", "links_to")
                .containsEntry("object_type", "note")
                .containsEntry("object_id", target.id())
                .containsEntry("object_label", "Проект X");

        NoteBacklinksResponse back = client().get().uri("/v1/notes/{id}/backlinks", target.id())
                .exchange().expectStatus().isOk()
                .expectBody(NoteBacklinksResponse.class).returnResult().getResponseBody();
        assertThat(back).isNotNull();
        assertThat(back.noteId()).isEqualTo(target.id());
        assertThat(back.backlinks()).extracting(NoteDto::id).containsExactly(source.id());
    }

    @Test
    void danglingLinkIsKeptAsALabelEdge() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "мысли", "надо спросить [[Неизвестное]]");

        List<Map<String, Object>> edges = edgesOf(note.id());
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0)).containsEntry("object_type", "label")
                .containsEntry("object_id", null)
                .containsEntry("object_label", "Неизвестное");
    }

    @Test
    void personLinkResolvesToCorePeople() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "подарок", "спросить у [[Мама]] про пионы");

        List<Map<String, Object>> edges = edgesOf(note.id());
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0)).containsEntry("object_type", "person")
                .containsEntry("object_id", MAMA_PERSON)
                .containsEntry("object_label", "Мама");
    }

    @Test
    void updateReplacesLinkEdges() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "черновик", "ссылка на [[Старое]]");
        assertThat(edgesOf(note.id())).extracting(m -> m.get("object_label")).containsExactly("Старое");

        client().put().uri("/v1/notes/{id}", note.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WriteNoteRequest(household, null, "черновик", "fact", null, "user", null,
                        "теперь про [[Новое]]", null))
                .exchange().expectStatus().isOk();

        // Old edge dropped, only the new link survives (re-seed, not accumulate).
        assertThat(edgesOf(note.id())).extracting(m -> m.get("object_label")).containsExactly("Новое");
    }

    @Test
    void deleteForgetsLinkEdges() {
        UUID household = freshHousehold();
        NoteDto note = createNote(household, "temp", "связано с [[Что-то]]");
        assertThat(edgesOf(note.id())).hasSize(1);

        client().delete().uri("/v1/notes/{id}", note.id())
                .exchange().expectStatus().isNoContent();

        assertThat(edgesOf(note.id())).isEmpty();
    }

    @Test
    void backlinks404OnUnknownNote() {
        client().get().uri("/v1/notes/{id}/backlinks", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }
}
