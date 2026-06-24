package dev.fedorov.ailife.mcp.creator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.creator.ContentPieceDto;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SaveContentPieceInput;
import dev.fedorov.ailife.contracts.creator.SaveTrendInput;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import dev.fedorov.ailife.contracts.creator.TrendDto;
import dev.fedorov.ailife.mcp.creator.tools.CreatorMcpTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions scope on
 * per-test households to stay deterministic (mirrors mcp-nutrition / mcp-wardrobe).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpCreatorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static UUID householdId;

    @Autowired CreatorMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @BeforeAll
    static void seedHousehold(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
    }

    @Test
    void setCreatorProfileUpsertsInPlaceWithJsonFields() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        CreatorProfileDto created = tools.setCreatorProfile(new SetCreatorProfileInput(
                h, owner, "English for IT", "junior devs", "friendly-expert",
                MAPPER.readTree("[\"youtube\",\"reddit\"]"), "grow to 10k",
                MAPPER.readTree("{\"noClickbait\":true}"), "ru/en"));
        assertThat(created.id()).isNotNull();
        assertThat(created.niche()).isEqualTo("English for IT");
        assertThat(created.tone()).isEqualTo("friendly-expert");
        assertThat(created.platforms().get(0).asText()).isEqualTo("youtube");
        assertThat(created.guardrails().get("noClickbait").asBoolean()).isTrue();

        // Same (household, owner) → updates the same row.
        CreatorProfileDto updated = tools.setCreatorProfile(new SetCreatorProfileInput(
                h, owner, "English for IT", "mid devs", "expert", null, null, null, null));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.audience()).isEqualTo("mid devs");
        assertThat(updated.tone()).isEqualTo("expert");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM creator.creator_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void getCreatorProfileReturnsNullWhenAbsentThenProfile() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getCreatorProfile(h, null)).isNull();

        tools.setCreatorProfile(new SetCreatorProfileInput(
                h, null, "household niche", null, null, null, null, null, "default track"));
        CreatorProfileDto got = tools.getCreatorProfile(h, null);
        assertThat(got).isNotNull();
        assertThat(got.niche()).isEqualTo("household niche");
    }

    @Test
    void setCreatorProfileRequiresHousehold() {
        assertThatThrownBy(() -> tools.setCreatorProfile(new SetCreatorProfileInput(
                null, null, "x", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("householdId");
    }

    @Test
    void saveTrendStoresFieldsAndDefaultsAndGuards() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        TrendDto saved = tools.saveTrend(new SaveTrendInput(
                h, null, "youtube", "YouTube", "AI pair programming blew up",
                "https://youtu.be/abc", "tools devs love now",
                MAPPER.readTree("{\"views\":120000}"), null));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.title()).isEqualTo("AI pair programming blew up");
        assertThat(saved.source()).isEqualTo("youtube");
        assertThat(saved.capturedAt()).isNotNull();       // defaulted to now
        assertThat(saved.metrics().get("views").asInt()).isEqualTo(120000);
        assertThat(saved.createdAt()).isNotNull();

        assertThatThrownBy(() -> tools.saveTrend(new SaveTrendInput(
                null, null, null, null, "t", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("householdId");
        assertThatThrownBy(() -> tools.saveTrend(new SaveTrendInput(
                h, null, null, null, " ", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void listTrendsScopesToHouseholdAndOwnerNewestFirst() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS);
        tools.saveTrend(new SaveTrendInput(h, owner, "reddit", "Reddit", "older", null, null, null, base));
        tools.saveTrend(new SaveTrendInput(h, owner, "reddit", "Reddit", "newer", null, null, null, base.plus(1, ChronoUnit.DAYS)));
        // A household-shared trend (no owner).
        tools.saveTrend(new SaveTrendInput(h, null, "web", "Web", "shared trend", null, null, null, base));

        List<TrendDto> all = tools.listTrends(h, null, null);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).title()).isEqualTo("newer");   // newest captured first

        // Owner scope drops the shared trend.
        assertThat(tools.listTrends(h, owner, null)).hasSize(2);
        // limit caps the result.
        assertThat(tools.listTrends(h, null, 1)).hasSize(1);

        // Another household doesn't leak in.
        UUID other = UUID.randomUUID();
        seedHousehold(other);
        tools.saveTrend(new SaveTrendInput(other, null, "web", "Web", "elsewhere", null, null, null, base));
        assertThat(tools.listTrends(h, null, null)).hasSize(3);
    }

    @Test
    void saveContentPieceDefaultsStatusAndListsByKindAndGets() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        ContentPieceDto idea = tools.saveContentPiece(new SaveContentPieceInput(
                h, null, "idea", "youtube", "5 git tricks", null, null, null, null, null));
        assertThat(idea.id()).isNotNull();
        assertThat(idea.kind()).isEqualTo("idea");
        assertThat(idea.status()).isEqualTo("new");          // defaulted

        ContentPieceDto draft = tools.saveContentPiece(new SaveContentPieceInput(
                h, null, "draft", "youtube", "Stop saying 'actually'", "Body text", "Subscribe",
                MAPPER.readTree("[\"#englishforit\",\"#devlife\"]"), "kept", UUID.randomUUID()));
        assertThat(draft.body()).isEqualTo("Body text");
        assertThat(draft.cta()).isEqualTo("Subscribe");
        assertThat(draft.status()).isEqualTo("kept");
        assertThat(draft.hashtags().get(0).asText()).isEqualTo("#englishforit");
        assertThat(draft.trendId()).isNotNull();

        // list all kinds, then scope to drafts.
        assertThat(tools.listContentPieces(h, null, null)).hasSize(2);
        List<ContentPieceDto> drafts = tools.listContentPieces(h, "draft", null);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).title()).isEqualTo("Stop saying 'actually'");

        // get by id, unknown → null.
        assertThat(tools.getContentPiece(draft.id())).isNotNull();
        assertThat(tools.getContentPiece(UUID.randomUUID())).isNull();

        assertThatThrownBy(() -> tools.saveContentPiece(new SaveContentPieceInput(
                h, null, null, null, "x", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void deleteContentPieceReturnsRowAndThrowsOnUnknown() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        ContentPieceDto piece = tools.saveContentPiece(new SaveContentPieceInput(
                h, null, "idea", null, "temp idea", null, null, null, null, null));
        ContentPieceDto deleted = tools.deleteContentPiece(piece.id());
        assertThat(deleted.id()).isEqualTo(piece.id());
        assertThatThrownBy(() -> tools.deleteContentPiece(piece.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void internalCreatorProfileEndpointUpsertsAndReads404ThenProfile() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer().baseUrl("http://localhost:" + port).build();

        CreatorProfileDto saved = client.post().uri("/internal/creator-profile")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new SetCreatorProfileInput(h, owner, "English for IT", "junior devs",
                        "friendly-expert", MAPPER.readTree("[\"youtube\"]"), null, null, "ru/en"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CreatorProfileDto.class)
                .returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.niche()).isEqualTo("English for IT");
        assertThat(saved.platforms().get(0).asText()).isEqualTo("youtube");

        // Second post for the same (household, owner) updates in place — still one row.
        client.post().uri("/internal/creator-profile")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new SetCreatorProfileInput(h, owner, "English for IT", "mid devs",
                        "expert", null, null, null, null))
                .exchange()
                .expectStatus().isOk();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM creator.creator_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);

        // Missing householdId → the tool's required-field guard surfaces as 400.
        client.post().uri("/internal/creator-profile")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new SetCreatorProfileInput(null, null, "x", null, null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        // GET — 404 for an unset person, 200 after the set.
        client.get().uri(b -> b.path("/internal/creator-profile")
                        .queryParam("householdId", h).queryParam("ownerId", UUID.randomUUID()).build())
                .exchange().expectStatus().isNotFound();
        CreatorProfileDto got = client.get()
                .uri(b -> b.path("/internal/creator-profile")
                        .queryParam("householdId", h).queryParam("ownerId", owner).build())
                .exchange().expectStatus().isOk()
                .expectBody(CreatorProfileDto.class).returnResult().getResponseBody();
        assertThat(got).isNotNull();
        assertThat(got.audience()).isEqualTo("mid devs");
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
