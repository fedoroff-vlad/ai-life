package dev.fedorov.ailife.mcp.briefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.mcp.briefing.tools.BriefingMcpTools;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions scope on
 * per-test households to stay deterministic (mirrors mcp-creator / mcp-nutrition).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpBriefingIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired BriefingMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    @Test
    void setBriefingProfileUpsertsInPlaceWithJsonAndScalarFields() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        BriefingProfileDto created = tools.setBriefingProfile(new SetBriefingProfileInput(
                h, owner, "Москва", 55.75, 37.62, "Europe/Moscow",
                MAPPER.readTree("[\"AI\",\"finance\"]"),
                MAPPER.readTree("[\"weather\",\"agenda\",\"finance\",\"news\"]"),
                "08:00", true, "morning person"));
        assertThat(created.id()).isNotNull();
        assertThat(created.locationLabel()).isEqualTo("Москва");
        assertThat(created.latitude()).isEqualTo(55.75);
        assertThat(created.timezone()).isEqualTo("Europe/Moscow");
        assertThat(created.interests().get(0).asText()).isEqualTo("AI");
        assertThat(created.sections()).hasSize(4);
        assertThat(created.scheduleTime()).isEqualTo("08:00");
        assertThat(created.scheduleEnabled()).isTrue();

        // Same (household, owner) → updates the same row.
        BriefingProfileDto updated = tools.setBriefingProfile(new SetBriefingProfileInput(
                h, owner, "Санкт-Петербург", 59.94, 30.31, "Europe/Moscow",
                null, null, "07:30", false, null));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.locationLabel()).isEqualTo("Санкт-Петербург");
        assertThat(updated.scheduleEnabled()).isFalse();

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM briefing.briefing_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void getBriefingProfileReturnsNullWhenAbsentThenProfile() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getBriefingProfile(h, null)).isNull();

        tools.setBriefingProfile(new SetBriefingProfileInput(
                h, null, "Berlin", 52.52, 13.41, "Europe/Berlin", null, null, null, null, "default"));
        BriefingProfileDto got = tools.getBriefingProfile(h, null);
        assertThat(got).isNotNull();
        assertThat(got.locationLabel()).isEqualTo("Berlin");
    }

    @Test
    void setBriefingProfileRequiresHousehold() {
        assertThatThrownBy(() -> tools.setBriefingProfile(new SetBriefingProfileInput(
                null, null, "x", null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("householdId");
    }

    @Test
    void listScheduledProfilesReturnsOnlyEnabledOnes() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID a = seedUser(h);
        UUID b = seedUser(h);
        tools.setBriefingProfile(new SetBriefingProfileInput(
                h, a, "Москва", 55.75, 37.62, "Europe/Moscow", null, null, "08:00", true, null));
        tools.setBriefingProfile(new SetBriefingProfileInput(
                h, b, "Москва", 55.75, 37.62, "Europe/Moscow", null, null, "09:00", false, null));

        List<BriefingProfileDto> scheduled = tools.listScheduledProfiles();
        assertThat(scheduled).extracting(BriefingProfileDto::ownerId).contains(a).doesNotContain(b);
    }

    @Test
    void internalBriefingProfileEndpointUpsertsAndReads404ThenProfile() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        BriefingProfileDto saved = client.post().uri("/internal/briefing-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetBriefingProfileInput(h, owner, "Москва", 55.75, 37.62,
                        "Europe/Moscow", MAPPER.readTree("[\"AI\"]"),
                        MAPPER.readTree("[\"weather\",\"news\"]"), "08:00", true, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(BriefingProfileDto.class)
                .returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.locationLabel()).isEqualTo("Москва");
        assertThat(saved.interests().get(0).asText()).isEqualTo("AI");

        // Second post for the same (household, owner) updates in place — still one row.
        client.post().uri("/internal/briefing-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetBriefingProfileInput(h, owner, "Berlin", 52.52, 13.41,
                        "Europe/Berlin", null, null, "07:00", true, null))
                .exchange()
                .expectStatus().isOk();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM briefing.briefing_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);

        // Missing householdId → the tool's required-field guard surfaces as 400.
        client.post().uri("/internal/briefing-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetBriefingProfileInput(null, null, "x", null, null, null, null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        // GET — 404 for an unset person, 200 after the set.
        client.get().uri(bld -> bld.path("/internal/briefing-profile")
                        .queryParam("householdId", h).queryParam("ownerId", UUID.randomUUID()).build())
                .exchange().expectStatus().isNotFound();
        BriefingProfileDto got = client.get()
                .uri(bld -> bld.path("/internal/briefing-profile")
                        .queryParam("householdId", h).queryParam("ownerId", owner).build())
                .exchange().expectStatus().isOk()
                .expectBody(BriefingProfileDto.class).returnResult().getResponseBody();
        assertThat(got).isNotNull();
        assertThat(got.locationLabel()).isEqualTo("Berlin");

        // The scheduled fan-out endpoint includes the enabled profile.
        List<BriefingProfileDto> scheduled = client.get().uri("/internal/briefing-profile/scheduled")
                .exchange().expectStatus().isOk()
                .expectBodyList(BriefingProfileDto.class).returnResult().getResponseBody();
        assertThat(scheduled).extracting(BriefingProfileDto::householdId).contains(h);
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
