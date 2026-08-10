package dev.fedorov.ailife.mcp.travel;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.mcp.travel.tools.TravelMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TR-b: the travel personalization store. Tests aren't isolated across methods (shared SpringBootTest
 * context + DB) — assertions scope on per-test households to stay deterministic (mirrors mcp-briefing).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpTravelIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TravelMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    @Test
    void setTravelProfileUpsertsInPlaceAndRoundTrips() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        TravelProfileDto created = tools.setTravelProfile(new SetTravelProfileInput(
                h, owner, "Москва", 55.75, 37.62,
                MAPPER.readTree("[\"beach\",\"family\"]"), "family",
                MAPPER.readTree("[4]"), new BigDecimal("200000.00"), "RUB", "любим спокойный отдых"));
        assertThat(created.id()).isNotNull();
        assertThat(created.homeBaseLabel()).isEqualTo("Москва");
        assertThat(created.homeBaseLatitude()).isEqualTo(55.75);
        assertThat(created.restTypes().get(0).asString()).isEqualTo("beach");
        assertThat(created.companions()).isEqualTo("family");
        assertThat(created.childAges().get(0).asInt()).isEqualTo(4);
        assertThat(created.budgetAmount()).isEqualByComparingTo("200000.00");
        assertThat(created.budgetCurrency()).isEqualTo("RUB");

        // getTravelProfile reads the same row back — rest_types/home_base intact.
        TravelProfileDto read = tools.getTravelProfile(h, owner);
        assertThat(read).isNotNull();
        assertThat(read.id()).isEqualTo(created.id());
        assertThat(read.homeBaseLabel()).isEqualTo("Москва");
        assertThat(read.restTypes()).hasSize(2);

        // Same (household, owner) → updates the same row, not a second one.
        TravelProfileDto updated = tools.setTravelProfile(new SetTravelProfileInput(
                h, owner, "Санкт-Петербург", 59.94, 30.31,
                MAPPER.readTree("[\"city\"]"), "couple", null, null, null, null));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.homeBaseLabel()).isEqualTo("Санкт-Петербург");
        assertThat(updated.companions()).isEqualTo("couple");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM travel.travel_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void getTravelProfileReturnsNullWhenAbsentThenProfile() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getTravelProfile(h, null)).isNull();

        tools.setTravelProfile(new SetTravelProfileInput(
                h, null, "Berlin", 52.52, 13.41, MAPPER.readTree("[\"city\"]"),
                "solo", null, null, null, "default"));
        TravelProfileDto got = tools.getTravelProfile(h, null);
        assertThat(got).isNotNull();
        assertThat(got.homeBaseLabel()).isEqualTo("Berlin");
    }

    @Test
    void setTravelProfileRequiresHousehold() {
        assertThatThrownBy(() -> tools.setTravelProfile(new SetTravelProfileInput(
                null, null, "x", null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("householdId");
    }

    @Test
    void internalTravelProfileEndpointUpsertsAndResolves204ForUnseenOwner() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        TravelProfileDto saved = client.post().uri("/internal/travel-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetTravelProfileInput(h, owner, "Москва", 55.75, 37.62,
                        MAPPER.readTree("[\"beach\"]"), "family", MAPPER.readTree("[4]"),
                        new BigDecimal("150000.00"), "RUB", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TravelProfileDto.class)
                .returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.homeBaseLabel()).isEqualTo("Москва");
        assertThat(saved.restTypes().get(0).asString()).isEqualTo("beach");

        // Second post for the same (household, owner) updates in place — still one row.
        client.post().uri("/internal/travel-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetTravelProfileInput(h, owner, "Berlin", 52.52, 13.41,
                        MAPPER.readTree("[\"city\"]"), "couple", null, null, null, null))
                .exchange()
                .expectStatus().isOk();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM travel.travel_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);

        // Missing householdId → the tool's required-field guard surfaces as 400.
        client.post().uri("/internal/travel-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetTravelProfileInput(null, null, "x", null, null, null, null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        // GET — 204 for an unseen owner (caller falls back to household default), 200 after the set.
        client.get().uri(bld -> bld.path("/internal/travel-profile")
                        .queryParam("householdId", h).queryParam("ownerId", UUID.randomUUID()).build())
                .exchange().expectStatus().isNoContent();
        TravelProfileDto got = client.get()
                .uri(bld -> bld.path("/internal/travel-profile")
                        .queryParam("householdId", h).queryParam("ownerId", owner).build())
                .exchange().expectStatus().isOk()
                .expectBody(TravelProfileDto.class).returnResult().getResponseBody();
        assertThat(got).isNotNull();
        assertThat(got.homeBaseLabel()).isEqualTo("Berlin");
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
