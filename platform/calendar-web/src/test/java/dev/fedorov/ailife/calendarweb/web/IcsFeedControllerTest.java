package dev.fedorov.ailife.calendarweb.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ICS feed end-to-end through the agent's HTTP surface, with mcp-caldav mocked (MockWebServer over
 * its {@code /internal/events} read passthrough). Asserts the feed resolves the token → household, reads
 * the window, and renders {@code text/calendar}; unknown token → 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IcsFeedControllerTest {

    static MockWebServer caldav;
    static final UUID HOUSEHOLD = UUID.randomUUID();
    static final String TOKEN = "s3cret-feed-token-abc123";

    @BeforeAll
    static void start() throws Exception {
        caldav = new MockWebServer();
        caldav.start();
    }

    @AfterAll
    static void stop() throws Exception {
        caldav.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("calendar-web.mcp-caldav-url", () -> "http://localhost:" + caldav.getPort());
        r.add("calendar-web.feeds[0].token", () -> TOKEN);
        r.add("calendar-web.feeds[0].household-id", () -> HOUSEHOLD.toString());
        r.add("calendar-web.feeds[0].label", () -> "Vlad");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void knownTokenServesIcsFeedFromCaldav() throws Exception {
        var events = List.of(new CalendarEventDto(
                UUID.randomUUID(), HOUSEHOLD, "ours", "uid-xyz",
                "ДР Маши", null, null,
                Instant.parse("2026-07-15T08:00:00Z"), Instant.parse("2026-07-15T09:00:00Z"),
                "FREQ=YEARLY", List.of("birthday"), null));
        caldav.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(events)));

        String body = http.get().uri("/ics/" + TOKEN + ".ics")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/calendar")
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(body)
                .contains("BEGIN:VCALENDAR")
                .contains("X-WR-CALNAME:Vlad")
                .contains("UID:uid-xyz")
                .contains("SUMMARY:ДР Маши")
                .contains("DTSTART:20260715T080000Z")
                .contains("RRULE:FREQ=YEARLY");

        // calendar-web read the right household over the deterministic passthrough.
        RecordedRequest req = caldav.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).startsWith("/internal/events");
        assertThat(req.getPath()).contains("householdId=" + HOUSEHOLD);
    }

    @Test
    void unknownTokenIs404() {
        http.get().uri("/ics/nope-not-a-real-token.ics")
                .exchange()
                .expectStatus().isNotFound();
    }
}
