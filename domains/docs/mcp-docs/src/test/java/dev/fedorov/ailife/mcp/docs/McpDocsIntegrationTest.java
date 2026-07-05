package dev.fedorov.ailife.mcp.docs;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import dev.fedorov.ailife.mcp.docs.tools.DocsMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions scope on
 * per-test households to stay deterministic (mirrors mcp-briefing / mcp-creator).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpDocsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired DocsMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    @Test
    void saveDocumentInsertsNewRowEachTime() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        DocumentDto saved = tools.saveDocument(new SaveDocumentInput(
                h, owner, "media-1", "receipt", "Пятёрочка чек", "Пятёрочка",
                LocalDate.of(2026, 6, 30), new BigDecimal("1234.56"), "RUB",
                "ПЯТЁРОЧКА молоко хлеб ИТОГО 1234.56", MAPPER.readTree("[\"groceries\"]")));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.mediaId()).isEqualTo("media-1");
        assertThat(saved.docType()).isEqualTo("receipt");
        assertThat(saved.amount()).isEqualByComparingTo("1234.56");
        assertThat(saved.tags().get(0).asText()).isEqualTo("groceries");
        assertThat(saved.createdAt()).isNotNull();

        // A second save of "the same" document is a distinct row (append-only).
        tools.saveDocument(new SaveDocumentInput(
                h, owner, "media-1", "receipt", "Пятёрочка чек", "Пятёрочка",
                LocalDate.of(2026, 6, 30), null, null, "again", null));
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM docs.document WHERE household_id = ?", Integer.class, h);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void getDocumentReturnsNullWhenAbsentThenDocument() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getDocument(UUID.randomUUID())).isNull();

        DocumentDto saved = tools.saveDocument(new SaveDocumentInput(
                h, null, "media-x", "contract", "Договор аренды", "ООО Ромашка",
                LocalDate.of(2026, 1, 15), null, null, "договор аренды квартиры", null));
        DocumentDto got = tools.getDocument(saved.id());
        assertThat(got).isNotNull();
        assertThat(got.title()).isEqualTo("Договор аренды");
    }

    @Test
    void saveDocumentRequiresHouseholdAndMedia() {
        assertThatThrownBy(() -> tools.saveDocument(new SaveDocumentInput(
                null, null, "m", null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("householdId");
        assertThatThrownBy(() -> tools.saveDocument(new SaveDocumentInput(
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mediaId");
    }

    @Test
    void listAndSearchFilterAndRank() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        tools.saveDocument(new SaveDocumentInput(h, null, "m1", "warranty",
                "Гарантия холодильник Bosch", "Bosch", LocalDate.of(2026, 3, 1),
                null, null, "гарантийный талон холодильник Bosch 24 месяца", null));
        tools.saveDocument(new SaveDocumentInput(h, null, "m2", "contract",
                "Договор аренды", "ООО Ромашка", LocalDate.of(2026, 1, 15),
                null, null, "договор аренды квартиры на год", null));

        // listDocuments narrows by docType.
        List<DocumentDto> warranties = tools.listDocuments(h, "warranty", null);
        assertThat(warranties).hasSize(1);
        assertThat(warranties.get(0).mediaId()).isEqualTo("m1");
        assertThat(tools.listDocuments(h, null, null)).hasSize(2);

        // searchDocuments matches the OCR text / party.
        List<DocumentDto> found = tools.searchDocuments(h, "холодильник", null, null);
        assertThat(found).extracting(DocumentDto::mediaId).containsExactly("m1");
        assertThat(tools.searchDocuments(h, "аренды", null, null))
                .extracting(DocumentDto::mediaId).containsExactly("m2");
        assertThat(tools.searchDocuments(h, "холодильник", "contract", null)).isEmpty();
        assertThat(tools.searchDocuments(h, "нетакого", null, null)).isEmpty();
    }

    @Test
    void internalDocumentsEndpointsSaveGetListSearch() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        DocumentDto saved = client.post().uri("/internal/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveDocumentInput(h, owner, "media-http", "note", "Больничный лист",
                        "Поликлиника №1", LocalDate.of(2026, 5, 2), null, null,
                        "больничный лист врач терапевт", MAPPER.readTree("[\"health\"]")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentDto.class).returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.title()).isEqualTo("Больничный лист");

        // GET by id.
        DocumentDto got = client.get().uri("/internal/documents/{id}", saved.id())
                .exchange().expectStatus().isOk()
                .expectBody(DocumentDto.class).returnResult().getResponseBody();
        assertThat(got).isNotNull();
        assertThat(got.mediaId()).isEqualTo("media-http");

        // GET unknown id → 404.
        client.get().uri("/internal/documents/{id}", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();

        // Missing mediaId → the tool's required-field guard surfaces as 400.
        client.post().uri("/internal/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveDocumentInput(h, null, null, null, null, null, null, null, null, null, null))
                .exchange().expectStatus().isBadRequest();

        // list + search.
        List<DocumentDto> listed = client.get()
                .uri(b -> b.path("/internal/documents").queryParam("householdId", h).build())
                .exchange().expectStatus().isOk()
                .expectBodyList(DocumentDto.class).returnResult().getResponseBody();
        assertThat(listed).extracting(DocumentDto::mediaId).contains("media-http");

        List<DocumentDto> searched = client.get()
                .uri(b -> b.path("/internal/documents/search")
                        .queryParam("householdId", h).queryParam("query", "терапевт").build())
                .exchange().expectStatus().isOk()
                .expectBodyList(DocumentDto.class).returnResult().getResponseBody();
        assertThat(searched).extracting(DocumentDto::mediaId).containsExactly("media-http");
    }

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }

    private UUID seedUser(UUID household) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.users (id, household_id, display_name) VALUES (?, ?, ?)",
                userId, household, "owner-" + userId);
        return userId;
    }
}
