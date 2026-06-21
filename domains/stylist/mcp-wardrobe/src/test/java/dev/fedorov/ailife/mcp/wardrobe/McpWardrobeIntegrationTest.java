package dev.fedorov.ailife.mcp.wardrobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.wardrobe.AddItemInput;
import dev.fedorov.ailife.contracts.wardrobe.SetStyleProfileInput;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
import dev.fedorov.ailife.contracts.wardrobe.UpdateItemInput;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import dev.fedorov.ailife.mcp.wardrobe.tools.WardrobeMcpTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions
 * scope on per-test households to stay deterministic (mirrors mcp-tasks).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpWardrobeIntegrationTest {

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

    @Autowired WardrobeMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @BeforeAll
    static void seedHousehold(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
    }

    @Test
    void addItemStoresDescriptiveFields() {
        WardrobeItemDto item = tools.addItem(new AddItemInput(
                householdId, null, "Navy wool coat", "outerwear", "navy", "wool",
                "plain", "winter", "smart", UUID.randomUUID()));
        assertThat(item.id()).isNotNull();
        assertThat(item.name()).isEqualTo("Navy wool coat");
        assertThat(item.category()).isEqualTo("outerwear");
        assertThat(item.colour()).isEqualTo("navy");
        assertThat(item.material()).isEqualTo("wool");
        assertThat(item.createdAt()).isNotNull();
    }

    @Test
    void addItemRequiresHouseholdAndName() {
        assertThatThrownBy(() -> tools.addItem(new AddItemInput(
                null, null, "x", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("householdId");
        assertThatThrownBy(() -> tools.addItem(new AddItemInput(
                householdId, null, " ", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void listItemsFiltersByCategoryAndScopesToHousehold() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        tools.addItem(new AddItemInput(h, null, "tee", "top", null, null, null, null, null, null));
        tools.addItem(new AddItemInput(h, null, "jeans", "bottom", null, null, null, null, null, null));

        assertThat(tools.listItems(h, null)).hasSize(2);
        assertThat(tools.listItems(h, "top"))
                .singleElement()
                .satisfies(i -> assertThat(i.name()).isEqualTo("tee"));

        // Another household's items don't leak in.
        UUID other = UUID.randomUUID();
        seedHousehold(other);
        tools.addItem(new AddItemInput(other, null, "scarf", "accessory", null, null, null, null, null, null));
        assertThat(tools.listItems(h, null)).hasSize(2);
    }

    @Test
    void updateItemAppliesNonNullFieldsOnly() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        WardrobeItemDto created = tools.addItem(new AddItemInput(
                h, null, "shirt", "top", "white", null, null, null, null, null));
        WardrobeItemDto updated = tools.updateItem(new UpdateItemInput(
                created.id(), null, null, "blue", "cotton", null, null, null, null));
        assertThat(updated.colour()).isEqualTo("blue");
        assertThat(updated.material()).isEqualTo("cotton");
        // Untouched fields stay.
        assertThat(updated.name()).isEqualTo("shirt");
        assertThat(updated.category()).isEqualTo("top");
    }

    @Test
    void deleteItemReturnsRowAndThrowsOnUnknown() {
        WardrobeItemDto item = tools.addItem(new AddItemInput(
                householdId, null, "temp", null, null, null, null, null, null, null));
        WardrobeItemDto deleted = tools.deleteItem(item.id());
        assertThat(deleted.id()).isEqualTo(item.id());
        assertThatThrownBy(() -> tools.deleteItem(item.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void setStyleProfileUpsertsInPlaceWithJsonFields() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID ownerId = seedUser(h);

        StyleProfileDto created = tools.setStyleProfile(new SetStyleProfileInput(
                h, ownerId, "classic", "rectangle", "winter",
                MAPPER.readTree("[\"wool\",\"silk\"]"), 180, new BigDecimal("75.50"),
                MAPPER.readTree("{\"chest\":100,\"waist\":82}"), "prefers dark tones", null));
        assertThat(created.id()).isNotNull();
        assertThat(created.colourType()).isEqualTo("winter");
        assertThat(created.heightCm()).isEqualTo(180);
        assertThat(created.weightKg()).isEqualByComparingTo("75.50");
        assertThat(created.suitableFabrics().get(0).asText()).isEqualTo("wool");
        assertThat(created.measurements().get("chest").asInt()).isEqualTo(100);

        // Same (household, owner) → updates the same row.
        StyleProfileDto updated = tools.setStyleProfile(new SetStyleProfileInput(
                h, ownerId, "dramatic", "hourglass", "autumn",
                null, 181, null, null, null, null));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.colourType()).isEqualTo("autumn");
        assertThat(updated.heightCm()).isEqualTo(181);

        // Exactly one row for this (household, owner).
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM wardrobe.style_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, ownerId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void getStyleProfileReturnsNullWhenAbsentThenTheProfile() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getStyleProfile(h, null)).isNull();

        tools.setStyleProfile(new SetStyleProfileInput(
                h, null, "natural", null, "summer", null, null, null, null, null, null));
        StyleProfileDto got = tools.getStyleProfile(h, null);
        assertThat(got).isNotNull();
        assertThat(got.colourType()).isEqualTo("summer");
    }

    @Test
    void internalItemEndpointAddsAnd400OnMissingName() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer().baseUrl("http://localhost:" + port).build();

        WardrobeItemDto added = client.post().uri("/internal/item")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new AddItemInput(h, null, "linen blazer", "outerwear",
                        "beige", "linen", null, "summer", "smart", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WardrobeItemDto.class)
                .returnResult().getResponseBody();
        assertThat(added).isNotNull();
        assertThat(added.name()).isEqualTo("linen blazer");
        assertThat(added.category()).isEqualTo("outerwear");

        // Missing name → the tool's required-field guard surfaces as 400.
        client.post().uri("/internal/item")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new AddItemInput(h, null, null, null, null, null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void internalProfileEndpointUpsertsForOwner() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID ownerId = seedUser(h);

        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer().baseUrl("http://localhost:" + port).build();

        StyleProfileDto saved = client.post().uri("/internal/profile")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new SetStyleProfileInput(h, ownerId, "classic", "rectangle", "winter",
                        MAPPER.readTree("[\"wool\"]"), 180, null,
                        MAPPER.readTree("{\"chest\":100}"), null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(StyleProfileDto.class)
                .returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.colourType()).isEqualTo("winter");
        assertThat(saved.heightCm()).isEqualTo(180);

        // Second post for the same (household, owner) updates in place — still one row.
        client.post().uri("/internal/profile")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new SetStyleProfileInput(h, ownerId, "dramatic", null, "autumn",
                        null, null, null, null, null, null))
                .exchange()
                .expectStatus().isOk();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM wardrobe.style_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, ownerId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void internalReadPassthroughsListItemsAndGetProfile() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        tools.addItem(new AddItemInput(h, null, "tee", "top", null, null, null, null, null, null));
        tools.addItem(new AddItemInput(h, null, "jeans", "bottom", null, null, null, null, null, null));

        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer().baseUrl("http://localhost:" + port).build();

        // GET /internal/items — all, then category-filtered.
        List<WardrobeItemDto> all = client.get()
                .uri(b -> b.path("/internal/items").queryParam("householdId", h).build())
                .exchange().expectStatus().isOk()
                .expectBodyList(WardrobeItemDto.class).returnResult().getResponseBody();
        assertThat(all).hasSize(2);
        List<WardrobeItemDto> tops = client.get()
                .uri(b -> b.path("/internal/items").queryParam("householdId", h)
                        .queryParam("category", "top").build())
                .exchange().expectStatus().isOk()
                .expectBodyList(WardrobeItemDto.class).returnResult().getResponseBody();
        assertThat(tops).singleElement().satisfies(i -> assertThat(i.name()).isEqualTo("tee"));

        // GET /internal/profile — 404 when unset, then 200 after a set.
        client.get().uri(b -> b.path("/internal/profile").queryParam("householdId", h).build())
                .exchange().expectStatus().isNotFound();
        tools.setStyleProfile(new SetStyleProfileInput(
                h, null, "natural", null, "summer", null, null, null, null, null, null));
        StyleProfileDto got = client.get()
                .uri(b -> b.path("/internal/profile").queryParam("householdId", h).build())
                .exchange().expectStatus().isOk()
                .expectBody(StyleProfileDto.class).returnResult().getResponseBody();
        assertThat(got).isNotNull();
        assertThat(got.colourType()).isEqualTo("summer");
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
