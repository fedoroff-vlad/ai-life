package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authored-notes tier CRUD (SB-1) in isolation. No LLM in this path (SB-1 does
 * not embed yet), so no MockWebServer for llm-gateway — the bean sits idle, same as
 * {@link RelationsIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.enabled=false")
class NotesIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
        registry.add("ailife.llm-client.base-url", () -> "http://127.0.0.1:1");
        // The SB-2 recall seed and SB-3 link resolution are best-effort; point both at a
        // fast-failing address so this SB-1 CRUD test never blocks on an external call.
        registry.add("memory.profile-base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    static UUID household;
    static UUID otherHousehold;
    static UUID owner;
    static UUID mama;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        household = UUID.randomUUID();
        otherHousehold = UUID.randomUUID();
        owner = UUID.randomUUID();
        mama = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "alpha");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", otherHousehold, "beta");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private NoteDto create(WriteNoteRequest req) {
        return client().post().uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(NoteDto.class)
                .returnResult().getResponseBody();
    }

    @Test
    void createPersistsAllManifestFieldsAndGetReturnsThem() {
        var req = new WriteNoteRequest(household, owner, "Мама — что любит", "person",
                List.of("person", "gift"), "user", mama,
                "Любит пионы 🌸. [[Мама]]", json.createObjectNode().put("mood", "warm"));

        NoteDto created = create(req);
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();

        NoteDto got = client().get().uri("/v1/notes/{id}", created.id())
                .exchange().expectStatus().isOk()
                .expectBody(NoteDto.class).returnResult().getResponseBody();

        assertThat(got).isNotNull();
        assertThat(got.householdId()).isEqualTo(household);
        assertThat(got.ownerId()).isEqualTo(owner);
        assertThat(got.title()).isEqualTo("Мама — что любит");
        assertThat(got.type()).isEqualTo("person");
        assertThat(got.tags()).containsExactly("person", "gift");
        assertThat(got.source()).isEqualTo("user");
        assertThat(got.personId()).isEqualTo(mama);
        assertThat(got.bodyMd()).contains("пионы").contains("[[Мама]]");
        assertThat(got.frontmatter().path("mood").asText()).isEqualTo("warm");
    }

    @Test
    void blankTypeAndSourceGetManifestDefaults() {
        var req = new WriteNoteRequest(household, null, "Купить лампочки", null,
                null, null, null, null, null);

        NoteDto created = create(req);
        assertThat(created.type()).isEqualTo("fact");
        assertThat(created.source()).isEqualTo("user");
        assertThat(created.ownerId()).isNull();          // household-shared
        assertThat(created.tags()).isEmpty();
    }

    @Test
    void updateReplacesMutableFieldsBumpsUpdatedAtAnd404OnUnknown() {
        NoteDto created = create(new WriteNoteRequest(household, owner, "draft", "idea",
                List.of("a"), "user", null, "первый вариант", null));

        var replace = new WriteNoteRequest(household, owner, "Идея для видео", "idea",
                List.of("content", "yt"), "creator-agent", null, "финальный текст", null);
        NoteDto updated = client().put().uri("/v1/notes/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(replace)
                .exchange().expectStatus().isOk()
                .expectBody(NoteDto.class).returnResult().getResponseBody();

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.title()).isEqualTo("Идея для видео");
        assertThat(updated.tags()).containsExactly("content", "yt");
        assertThat(updated.source()).isEqualTo("creator-agent");
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());

        client().put().uri("/v1/notes/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(replace)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void listReturnsHouseholdNotesNewestFirstAndDoesNotLeakAcrossHouseholds() {
        create(new WriteNoteRequest(otherHousehold, null, "should-not-leak", "fact",
                null, "user", null, null, null));
        NoteDto older = create(new WriteNoteRequest(household, null, "older-note", "fact",
                null, "user", null, null, null));
        NoteDto newer = create(new WriteNoteRequest(household, null, "newer-note", "fact",
                null, "user", null, null, null));

        List<NoteDto> notes = client().get()
                .uri(uri -> uri.path("/v1/notes").queryParam("householdId", household).build())
                .exchange().expectStatus().isOk()
                .expectBodyList(NoteDto.class).returnResult().getResponseBody();

        assertThat(notes).isNotNull();
        assertThat(notes).extracting(NoteDto::title).contains("newer-note", "older-note");
        assertThat(notes).extracting(NoteDto::title).doesNotContain("should-not-leak");
        // Newest-first: newer appears before older.
        List<String> titles = notes.stream().map(NoteDto::title).toList();
        assertThat(titles.indexOf("newer-note")).isLessThan(titles.indexOf("older-note"));
        assertThat(newer.id()).isNotEqualTo(older.id());
    }

    @Test
    void forgetRemovesRowAnd404OnUnknown() {
        NoteDto created = create(new WriteNoteRequest(household, null, "temp", "fact",
                null, "user", null, null, null));

        client().delete().uri("/v1/notes/{id}", created.id())
                .exchange().expectStatus().isNoContent();

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM memory.note WHERE id = ?", Integer.class, created.id());
        assertThat(rowCount).isEqualTo(0);

        client().delete().uri("/v1/notes/{id}", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void resurfaceReturnsAStaleNoteButNotAFreshOneAnd204WhenNothingStale() {
        // Nothing stale yet in a brand-new household → 204.
        UUID emptyHousehold = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", emptyHousehold, "gamma");
        client().get()
                .uri(uri -> uri.path("/v1/notes/resurface").queryParam("householdId", emptyHousehold).build())
                .exchange().expectStatus().isNoContent();

        // One old note (backdated) + one fresh note in the same household.
        NoteDto stale = create(new WriteNoteRequest(emptyHousehold, null, "старая заметка", "fact",
                null, "user", null, "давно записано", null));
        jdbc.update("UPDATE memory.note SET updated_at = now() - interval '30 days' WHERE id = ?", stale.id());
        create(new WriteNoteRequest(emptyHousehold, null, "свежая заметка", "fact",
                null, "user", null, "только что", null));

        // olderThanDays=7 → only the backdated note qualifies; the fresh one is excluded.
        NoteDto surfaced = client().get()
                .uri(uri -> uri.path("/v1/notes/resurface")
                        .queryParam("householdId", emptyHousehold)
                        .queryParam("olderThanDays", 7).build())
                .exchange().expectStatus().isOk()
                .expectBody(NoteDto.class).returnResult().getResponseBody();

        assertThat(surfaced).isNotNull();
        assertThat(surfaced.id()).isEqualTo(stale.id());
        assertThat(surfaced.title()).isEqualTo("старая заметка");
    }

    @Test
    void resurfaceDoesNotLeakAcrossHouseholds() {
        UUID hhA = UUID.randomUUID();
        UUID hhB = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hhA, "res-a");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hhB, "res-b");

        NoteDto onlyInA = create(new WriteNoteRequest(hhA, null, "A-note", "fact", null, "user", null, "a", null));
        jdbc.update("UPDATE memory.note SET updated_at = now() - interval '60 days' WHERE id = ?", onlyInA.id());

        // Household B has no stale note → 204, and A's note never surfaces for B.
        client().get()
                .uri(uri -> uri.path("/v1/notes/resurface")
                        .queryParam("householdId", hhB).queryParam("olderThanDays", 1).build())
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void createRejectsMissingTitle() {
        var bad = new WriteNoteRequest(household, null, "  ", "fact", null, "user", null, null, null);
        client().post().uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(bad)
                .exchange().expectStatus().is5xxServerError();
    }
}
