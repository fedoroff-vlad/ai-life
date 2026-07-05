package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SB-7 markdown-vault export (second-brain epic #257 closer). Creates notes, hits
 * {@code GET /v1/notes/export}, unzips the result and asserts each note round-trips: a {@code .md}
 * file per note with a YAML frontmatter header ({@code id} anchor + manifest fields, including the
 * open frontmatter-bag extras) and the body verbatim ({@code [[wiki-links]]} intact). The export is
 * household-scoped — another household's notes never leak in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.enabled=false")
@AutoConfigureWebTestClient
class NoteExportIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
        // The SB-2 recall seed + SB-3 link resolution are best-effort; fast-fail both so create() never blocks.
        registry.add("ailife.llm-client.base-url", () -> "http://127.0.0.1:1");
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
                .exchange().expectStatus().isOk()
                .expectBody(NoteDto.class).returnResult().getResponseBody();
    }

    @Test
    void exportRendersEachNoteAsRoundTrippableMarkdown() throws Exception {
        NoteDto person = create(new WriteNoteRequest(household, owner, "Мама — что любит", "person",
                List.of("person", "gift"), "user", mama,
                "Любит пионы 🌸. Предпочитает в горшке, не срезку.\nСвязано: [[Мама]]",
                json.createObjectNode().put("mood", "warm")));
        create(new WriteNoteRequest(household, null, "Купить лампочки", "fact",
                null, "user", null, "E27, тёплый свет", null));
        // A note in another household must NOT appear in this household's vault.
        create(new WriteNoteRequest(otherHousehold, null, "should-not-leak", "fact",
                null, "user", null, "secret", null));

        byte[] zipBytes = client().get()
                .uri(uri -> uri.path("/v1/notes/export").queryParam("householdId", household).build())
                .exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.parseMediaType("application/zip"))
                .expectBody(byte[].class).returnResult().getResponseBody();

        Map<String, String> vault = unzip(zipBytes);

        // One .md per household note; the other household's note is absent.
        assertThat(vault).hasSize(2);
        assertThat(vault.keySet()).allMatch(name -> name.endsWith(".md"));
        assertThat(vault.keySet()).anySatisfy(name -> assertThat(name).contains("Мама — что любит"));
        assertThat(vault.values()).noneMatch(md -> md.contains("should-not-leak"));

        String personMd = vault.entrySet().stream()
                .filter(e -> e.getKey().contains("Мама"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();

        // Frontmatter is a parseable YAML block whose id anchors back to the row (round-trip).
        Map<String, Object> fm = frontmatter(personMd);
        assertThat(fm.get("id")).isEqualTo(person.id().toString());
        assertThat(fm.get("title")).isEqualTo("Мама — что любит");
        assertThat(fm.get("type")).isEqualTo("person");
        assertThat(fm.get("tags")).isEqualTo(List.of("person", "gift"));
        assertThat(fm.get("source")).isEqualTo("user");
        assertThat(fm.get("person")).isEqualTo(mama.toString());
        assertThat(fm.get("owner")).isEqualTo(owner.toString());
        assertThat(fm).containsEntry("mood", "warm");   // the open frontmatter-bag extra survives

        // The body is preserved verbatim, wiki-link intact for a vault frontend to traverse.
        String body = personMd.substring(personMd.indexOf("---", 3) + 4);
        assertThat(body).contains("не срезку").contains("[[Мама]]");
    }

    /** Parse the leading {@code ---}…{@code ---} YAML block of a rendered note. */
    private static Map<String, Object> frontmatter(String md) {
        int start = md.indexOf("---");
        int end = md.indexOf("---", start + 3);
        String yaml = md.substring(start + 3, end);
        return new Yaml().load(yaml);
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
