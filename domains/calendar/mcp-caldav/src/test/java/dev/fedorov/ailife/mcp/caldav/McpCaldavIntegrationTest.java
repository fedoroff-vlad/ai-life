package dev.fedorov.ailife.mcp.caldav;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import dev.fedorov.ailife.contracts.calendar.CreateFeedInput;
import dev.fedorov.ailife.contracts.calendar.ListEventsInput;
import dev.fedorov.ailife.contracts.calendar.SearchEventsInput;
import dev.fedorov.ailife.contracts.calendar.UpdateEventInput;
import dev.fedorov.ailife.mcp.caldav.config.McpCaldavProperties;
import dev.fedorov.ailife.mcp.caldav.tools.CalendarMcpTools;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpCaldavIntegrationTest extends AbstractPostgresIntegrationTest {

    @Container
    static GenericContainer<?> radicale = new GenericContainer<>("tomsquest/docker-radicale:latest")
            .withExposedPorts(5232)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("radicale-config"),
                    "/etc/radicale/config")
            // tomsquest/docker-radicale ships without bash, so the default
            // InternalCommandPortListeningCheck no-ops and the wait returns far too
            // early. Instead poll Radicale's "/.web/" endpoint with HTTP — it returns
            // 200 only once the Python server has actually bound to 5232.
            .waitingFor(Wait.forHttp("/.web/")
                    .forStatusCodeMatching(c -> c >= 200 && c < 500)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
        registry.add("caldav.url", () -> "http://" + radicale.getHost() + ":" + radicale.getMappedPort(5232));
    }

    static UUID householdId;

    @Autowired CalendarMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired McpCaldavProperties caldavProps;
    @LocalServerPort int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @BeforeAll
    static void seedHousehold(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
    }

    @Test
    void createUpdateDeleteFlowAndCacheMirror() {
        Instant start = Instant.parse("2027-07-15T08:00:00Z");
        Instant end = start.plus(1, ChronoUnit.HOURS);

        // create
        CalendarEventDto created = tools.createEvent(new CreateEventInput(
                householdId,
                "ДР Маши",
                "yearly",
                "home",
                start, end,
                "FREQ=YEARLY",
                List.of("birthday"),
                null));

        assertThat(created.id()).isNotNull();
        assertThat(created.summary()).isEqualTo("ДР Маши");
        assertThat(created.categories()).containsExactly("birthday");

        // cache row exists
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.events_cache WHERE id = ?",
                Integer.class, created.id());
        assertThat(rows).isEqualTo(1);

        // upstream PUT actually hit Radicale
        Integer radicaleHit = jdbc.queryForObject(
                "SELECT length(raw_ics) FROM calendar.events_cache WHERE id = ?",
                Integer.class, created.id());
        assertThat(radicaleHit).isGreaterThan(50);  // ICS body is non-trivial

        // list in range
        List<CalendarEventDto> inRange = tools.listEvents(new ListEventsInput(
                householdId,
                Instant.parse("2027-07-01T00:00:00Z"),
                Instant.parse("2027-08-01T00:00:00Z")));
        assertThat(inRange).hasSize(1);
        assertThat(inRange.get(0).id()).isEqualTo(created.id());

        // search by fuzzy summary
        List<CalendarEventDto> hits = tools.searchEvents(new SearchEventsInput(householdId, "маша"));
        assertThat(hits).extracting(CalendarEventDto::id).contains(created.id());

        // update
        CalendarEventDto updated = tools.updateEvent(new UpdateEventInput(
                created.id(),
                "ДР Маши — 35",
                null, null,
                null, null, null, null));
        assertThat(updated.summary()).isEqualTo("ДР Маши — 35");

        // delete
        boolean removed = tools.deleteEvent(created.id());
        assertThat(removed).isTrue();
        Integer after = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.events_cache WHERE id = ?",
                Integer.class, created.id());
        assertThat(after).isEqualTo(0);
    }

    @Test
    void deleteOnUnknownIdReturnsFalse() {
        boolean removed = tools.deleteEvent(UUID.randomUUID());
        assertThat(removed).isFalse();
    }

    @Test
    void internalEventPostCreatesEventAndMirrorsCache() {
        Instant start = Instant.parse("2027-09-10T18:00:00Z");
        var input = new CreateEventInput(
                householdId, "Pay rent", null, null,
                start, start.plus(1, ChronoUnit.HOURS), null, null, null);

        CalendarEventDto created = client().post().uri("/internal/event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .exchange()
                .expectStatus().isOk()
                .expectBody(CalendarEventDto.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.calendarUid()).isNotBlank();
        assertThat(created.summary()).isEqualTo("Pay rent");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.events_cache WHERE id = ?",
                Integer.class, created.id());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void internalEventsGetReturnsEventsInWindow() {
        Instant start = Instant.parse("2028-03-15T09:00:00Z");
        tools.createEvent(new CreateEventInput(
                householdId, "Dentist appointment", null, null,
                start, start.plus(1, ChronoUnit.HOURS), null, null, null));

        List<CalendarEventDto> events = client().get()
                .uri(b -> b.path("/internal/events")
                        .queryParam("householdId", householdId)
                        .queryParam("from", "2028-03-01T00:00:00Z")
                        .queryParam("to", "2028-04-01T00:00:00Z")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(CalendarEventDto.class)
                .returnResult().getResponseBody();

        assertThat(events).isNotNull();
        assertThat(events).extracting(CalendarEventDto::summary).contains("Dentist appointment");
    }

    @Test
    void internalEventsGetRejectsBadInstants() {
        client().get()
                .uri(b -> b.path("/internal/events")
                        .queryParam("householdId", householdId)
                        .queryParam("from", "not-a-date")
                        .queryParam("to", "2028-04-01T00:00:00Z")
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void feedMintResolveListRevokeFlow() {
        CalendarFeedDto feed = client().post().uri("/internal/feeds")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFeedInput(householdId, null, "Vlad"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CalendarFeedDto.class)
                .returnResult().getResponseBody();
        assertThat(feed).isNotNull();
        assertThat(feed.token()).isNotBlank();
        assertThat(feed.householdId()).isEqualTo(householdId);
        assertThat(feed.label()).isEqualTo("Vlad");

        // resolve by token
        client().get().uri("/internal/feeds/" + feed.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody(CalendarFeedDto.class)
                .value(f -> assertThat(f.householdId()).isEqualTo(householdId));

        // list for the household contains it
        List<CalendarFeedDto> feeds = client().get()
                .uri(b -> b.path("/internal/feeds").queryParam("householdId", householdId).build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(CalendarFeedDto.class)
                .returnResult().getResponseBody();
        assertThat(feeds).extracting(CalendarFeedDto::id).contains(feed.id());

        // revoke → the token no longer resolves
        client().delete().uri("/internal/feeds/" + feed.id())
                .exchange()
                .expectStatus().isNoContent();
        client().get().uri("/internal/feeds/" + feed.token())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void resolveUnknownTokenIs404() {
        client().get().uri("/internal/feeds/definitely-not-a-real-token")
                .exchange()
                .expectStatus().isNotFound();
    }
}
