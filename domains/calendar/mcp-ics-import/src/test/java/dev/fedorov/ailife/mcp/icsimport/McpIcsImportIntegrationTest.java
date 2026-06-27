package dev.fedorov.ailife.mcp.icsimport;

import dev.fedorov.ailife.contracts.calendar.AddSubscriptionInput;
import dev.fedorov.ailife.contracts.calendar.IcsSubscriptionDto;
import dev.fedorov.ailife.contracts.calendar.PullCalendarResult;
import dev.fedorov.ailife.mcp.icsimport.tools.IcsImportMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpIcsImportIntegrationTest extends AbstractPostgresIntegrationTest {

    @Container
    static GenericContainer<?> radicale = new GenericContainer<>("tomsquest/docker-radicale:latest")
            .withExposedPorts(5232)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("radicale-config"),
                    "/etc/radicale/config")
            .waitingFor(Wait.forHttp("/.web/")
                    .forStatusCodeMatching(c -> c >= 200 && c < 500)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static MockWebServer icsServer;
    static MockWebServer schedulerServer;

    /** Last seen scheduler request — handy for assertions in delete tests. */
    static volatile RecordedRequest lastSchedulerRequest;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) throws IOException {
        icsServer = new MockWebServer();
        icsServer.start();
        schedulerServer = new MockWebServer();
        schedulerServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                lastSchedulerRequest = req;
                if ("POST".equals(req.getMethod())) {
                    // Echo a ScheduleDto with a stable id derived from the request count.
                    UUID id = UUID.randomUUID();
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody("{\"id\":\"" + id + "\",\"enabled\":true}");
                }
                if ("DELETE".equals(req.getMethod())) {
                    return new MockResponse().setResponseCode(204);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        schedulerServer.start();
        registerDataSource(registry);
        registry.add("icsimport.caldav-url",
                () -> "http://" + radicale.getHost() + ":" + radicale.getMappedPort(5232));
        registry.add("icsimport.scheduler-url",
                () -> "http://localhost:" + schedulerServer.getPort());
    }

    @AfterAll
    static void stopMock() throws IOException {
        if (icsServer != null) icsServer.shutdown();
        if (schedulerServer != null) schedulerServer.shutdown();
    }

    static UUID householdId;

    @Autowired IcsImportMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void seedHousehold(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
    }

    private static String icsWith(String... uidsAndSummaries) {
        StringBuilder sb = new StringBuilder()
                .append("BEGIN:VCALENDAR\r\n")
                .append("PRODID:-//test//EN\r\n")
                .append("VERSION:2.0\r\n");
        for (int i = 0; i < uidsAndSummaries.length; i += 2) {
            String uid = uidsAndSummaries[i];
            String summary = uidsAndSummaries[i + 1];
            sb.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(uid).append("\r\n")
                    .append("DTSTAMP:20270101T000000Z\r\n")
                    .append("DTSTART:20270715T080000Z\r\n")
                    .append("DTEND:20270715T090000Z\r\n")
                    .append("SUMMARY:").append(summary).append("\r\n")
                    .append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    @Test
    @Order(1)
    void addSubscriptionPullsEventsAndRegistersHourlyCron() throws Exception {
        icsServer.enqueue(new MockResponse()
                .setBody(icsWith("uid-1@test", "Public holiday", "uid-2@test", "Concert"))
                .setHeader("Content-Type", "text/calendar"));

        String url = icsServer.url("/feed.ics").toString();
        IcsSubscriptionDto sub = tools.addSubscription(
                new AddSubscriptionInput(householdId, "Work calendar", url));

        assertThat(sub.id()).isNotNull();
        assertThat(sub.slug()).isEqualTo("work-calendar");
        assertThat(sub.lastError()).isNull();
        assertThat(sub.lastSyncedAt()).isNotNull();

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.events_cache WHERE household_id = ? AND source_calendar = ?",
                Integer.class, householdId, "external-work-calendar");
        assertThat(rows).isEqualTo(2);

        // Scheduler received the auto-registration POST with the right shape.
        RecordedRequest schedReq = schedulerServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(schedReq).isNotNull();
        assertThat(schedReq.getMethod()).isEqualTo("POST");
        assertThat(schedReq.getPath()).isEqualTo("/v1/schedules");
        String body = schedReq.getBody().readUtf8();
        assertThat(body)
                .contains("\"cron\":\"0 0 * * * *\"")
                .contains("\"kind\":\"ics.pull\"")
                .contains("\"ownerAgent\":\"calendar\"")
                .contains("\"subscriptionId\":\"" + sub.id() + "\"");

        // schedule_id was persisted on the subscription row.
        UUID scheduleId = jdbc.queryForObject(
                "SELECT schedule_id FROM calendar.ics_subscriptions WHERE id = ?",
                UUID.class, sub.id());
        assertThat(scheduleId).isNotNull();

        // Subscription persisted.
        List<IcsSubscriptionDto> listed = tools.listSubscriptions(householdId);
        assertThat(listed).extracting(IcsSubscriptionDto::slug).contains("work-calendar");
    }

    @Test
    @Order(2)
    void pullRemovesEventsThatVanishedFromFeed() {
        // Feed now only contains uid-1; uid-2 must be removed.
        icsServer.enqueue(new MockResponse()
                .setBody(icsWith("uid-1@test", "Public holiday — renamed"))
                .setHeader("Content-Type", "text/calendar"));

        IcsSubscriptionDto sub = tools.listSubscriptions(householdId).get(0);
        PullCalendarResult result = tools.pullCalendar(sub.id());

        assertThat(result.eventsUpserted()).isEqualTo(1);
        assertThat(result.eventsRemoved()).isEqualTo(1);
        assertThat(result.error()).isNull();

        String summary = jdbc.queryForObject(
                "SELECT summary FROM calendar.events_cache WHERE calendar_uid = ?",
                String.class, "uid-1@test");
        assertThat(summary).isEqualTo("Public holiday — renamed");

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.events_cache WHERE household_id = ? AND source_calendar = ?",
                Integer.class, householdId, "external-work-calendar");
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    @Order(3)
    void pullRecordsLastErrorOnFetchFailure() {
        icsServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        IcsSubscriptionDto sub = tools.listSubscriptions(householdId).get(0);
        PullCalendarResult result = tools.pullCalendar(sub.id());

        assertThat(result.error()).isNotBlank();
        IcsSubscriptionDto refreshed = tools.listSubscriptions(householdId).get(0);
        assertThat(refreshed.lastError()).isNotBlank();
    }

    @Test
    @Order(4)
    void internalPullEndpointFetchesAndUpserts() throws Exception {
        IcsSubscriptionDto sub = tools.listSubscriptions(householdId).get(0);

        icsServer.enqueue(new MockResponse()
                .setBody(icsWith("uid-1@test", "Public holiday — final"))
                .setHeader("Content-Type", "text/calendar"));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        client.post().uri("/internal/pull/{id}", sub.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.subscriptionId").isEqualTo(sub.id().toString())
                .jsonPath("$.eventsUpserted").isEqualTo(1);

        String summary = jdbc.queryForObject(
                "SELECT summary FROM calendar.events_cache WHERE calendar_uid = ?",
                String.class, "uid-1@test");
        assertThat(summary).isEqualTo("Public holiday — final");
    }

    @Test
    @Order(5)
    void internalPullUnknownIdReturns404() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        client.post().uri("/internal/pull/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @Order(6)
    void removeSubscriptionDropsRowMirroredEventsAndDeletesCron() throws Exception {
        IcsSubscriptionDto sub = tools.listSubscriptions(householdId).get(0);
        boolean removed = tools.removeSubscription(sub.id());
        assertThat(removed).isTrue();

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.events_cache WHERE household_id = ? AND source_calendar = ?",
                Integer.class, householdId, "external-work-calendar");
        assertThat(events).isEqualTo(0);

        Integer subs = jdbc.queryForObject(
                "SELECT count(*) FROM calendar.ics_subscriptions WHERE household_id = ?",
                Integer.class, householdId);
        assertThat(subs).isEqualTo(0);

        // Drain scheduler queue and locate the DELETE — there may be earlier POSTs
        // from preceding tests' add/internal-pull flow.
        RecordedRequest req;
        boolean sawDelete = false;
        while ((req = schedulerServer.takeRequest(500, TimeUnit.MILLISECONDS)) != null) {
            if ("DELETE".equals(req.getMethod()) && req.getPath().startsWith("/v1/schedules/")) {
                sawDelete = true;
                break;
            }
        }
        assertThat(sawDelete).as("expected scheduler DELETE on remove_subscription").isTrue();
    }

    @Test
    @Order(7)
    void removeSubscriptionUnknownIdReturnsFalse() {
        assertThat(tools.removeSubscription(UUID.randomUUID())).isFalse();
    }
}
