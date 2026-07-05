package dev.fedorov.ailife.mcp.briefing;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.mcp.briefing.tools.BriefingMcpTools;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions scope on
 * per-test households to stay deterministic (mirrors mcp-creator / mcp-nutrition).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpBriefingIntegrationTest extends AbstractPostgresIntegrationTest {

    /** scheduler-service stub: echo a ScheduleDto on POST /v1/schedules, 204 on DELETE. */
    private static final ObjectMapper SCHEDULER_MAPPER = new ObjectMapper();
    private static final Dispatcher SCHEDULER_DISPATCHER = new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            String path = recordedRequest.getPath() == null ? "" : recordedRequest.getPath();
            if ("POST".equals(recordedRequest.getMethod()) && "/v1/schedules".equals(path)) {
                ScheduleDto dto = new ScheduleDto(UUID.randomUUID(), null, "briefing",
                        "briefing.digest", "0 0 5 * * *", null, true, Instant.now(), null, Instant.now());
                try {
                    return new MockResponse().setHeader("content-type", "application/json")
                            .setBody(SCHEDULER_MAPPER.writeValueAsString(dto));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
            if ("DELETE".equals(recordedRequest.getMethod()) && path.startsWith("/v1/schedules/")) {
                return new MockResponse().setResponseCode(204);
            }
            return new MockResponse().setResponseCode(404);
        }
    };

    // Started in a static initializer so the port is known when Spring resolves
    // the @DynamicPropertySource supplier during context refresh (before @BeforeAll).
    // Mirrors mcp-finance's scheduler stub.
    static final MockWebServer scheduler;
    static {
        scheduler = new MockWebServer();
        scheduler.setDispatcher(SCHEDULER_DISPATCHER);
        try {
            scheduler.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
        registry.add("mcp-briefing.scheduler-url",
                () -> "http://localhost:" + scheduler.getPort());
    }

    @AfterAll
    static void stopScheduler() throws Exception {
        scheduler.shutdown();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired BriefingMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    @BeforeEach
    void drainScheduler() throws Exception {
        // Context is reused across methods; drop any scheduler requests a prior
        // test left recorded so per-test assertions start clean.
        while (scheduler.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
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
    void setBriefingProfileRegistersCronOnEnableAndDeletesOnDisable() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        // Enable at 08:00 Europe/Moscow (UTC+3) → registers a briefing.digest cron at 05:00 UTC.
        BriefingProfileDto enabled = tools.setBriefingProfile(new SetBriefingProfileInput(
                h, owner, "Москва", 55.75, 37.62, "Europe/Moscow", null, null, "08:00", true, null));
        assertThat(enabled.scheduleEnabled()).isTrue();

        RecordedRequest register = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(register).isNotNull();
        assertThat(register.getMethod()).isEqualTo("POST");
        assertThat(register.getPath()).isEqualTo("/v1/schedules");
        var body = MAPPER.readTree(register.getBody().readUtf8());
        assertThat(body.path("ownerAgent").asText()).isEqualTo("briefing");
        assertThat(body.path("kind").asText()).isEqualTo("briefing.digest");
        assertThat(body.path("cron").asText()).isEqualTo("0 0 5 * * *");
        assertThat(body.path("householdId").asText()).isEqualTo(h.toString());
        assertThat(body.path("payload").path("ownerId").asText()).isEqualTo(owner.toString());

        // Re-save disabled → deletes the stored schedule id, no fresh register.
        tools.setBriefingProfile(new SetBriefingProfileInput(
                h, owner, "Москва", 55.75, 37.62, "Europe/Moscow", null, null, "08:00", false, null));
        RecordedRequest delete = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(delete).isNotNull();
        assertThat(delete.getMethod()).isEqualTo("DELETE");
        assertThat(delete.getPath()).startsWith("/v1/schedules/");
        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
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
