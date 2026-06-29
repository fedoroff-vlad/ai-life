package dev.fedorov.ailife.calendarweb.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import okhttp3.mockwebserver.Dispatcher;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ICS feed end-to-end with mcp-caldav mocked. Covers both token sources (track B): the persistent
 * store ({@code /internal/feeds/{token}}) and the env-configured fallback, plus an unknown token → 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IcsFeedControllerTest {

    static MockWebServer caldav;
    static final UUID HOUSEHOLD = UUID.randomUUID();
    static final String DB_TOKEN = "db-minted-token-aaa";
    static final String ENV_TOKEN = "env-static-token-bbb";
    static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void start() throws Exception {
        caldav = new MockWebServer();
        caldav.setDispatcher(new CaldavDispatcher());
        caldav.start();
    }

    @AfterAll
    static void stop() throws Exception {
        caldav.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("calendar-web.mcp-caldav-url", () -> "http://localhost:" + caldav.getPort());
        // A static env fallback feed (the persistent store doesn't know ENV_TOKEN).
        r.add("calendar-web.feeds[0].token", () -> ENV_TOKEN);
        r.add("calendar-web.feeds[0].household-id", () -> HOUSEHOLD.toString());
        r.add("calendar-web.feeds[0].label", () -> "Static");
    }

    @Autowired WebTestClient http;

    @Test
    void mintedTokenResolvesFromStoreAndServesIcs() {
        String body = http.get().uri("/ics/" + DB_TOKEN + ".ics")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/calendar")
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(body)
                .contains("X-WR-CALNAME:Vlad")          // label came from the store
                .contains("SUMMARY:ДР Маши")
                .contains("DTSTART:20260715T080000Z");
    }

    @Test
    void envTokenIsTheFallbackWhenStoreReturns404() {
        String body = http.get().uri("/ics/" + ENV_TOKEN + ".ics")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/calendar")
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(body).contains("X-WR-CALNAME:Static").contains("SUMMARY:ДР Маши");
    }

    @Test
    void unknownTokenIs404() {
        http.get().uri("/ics/totally-unknown-token.ics")
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Serves both calendar-web reads: feed resolution and the events window. */
    static final class CaldavDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.startsWith("/internal/feeds/" + DB_TOKEN)) {
                    var feed = new CalendarFeedDto(UUID.randomUUID(), HOUSEHOLD, null, "Vlad",
                            DB_TOKEN, Instant.now(), null);
                    return json(M.writeValueAsString(feed));
                }
                if (path.startsWith("/internal/feeds/")) {
                    return new MockResponse().setResponseCode(404);   // unknown/revoked → env fallback
                }
                if (path.startsWith("/internal/events")) {
                    var events = List.of(new CalendarEventDto(
                            UUID.randomUUID(), HOUSEHOLD, "ours", "uid-xyz", "ДР Маши", null, null,
                            Instant.parse("2026-07-15T08:00:00Z"), Instant.parse("2026-07-15T09:00:00Z"),
                            "FREQ=YEARLY", List.of("birthday"), null));
                    return json(M.writeValueAsString(events));
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(String body) {
            return new MockResponse().setHeader("content-type", "application/json").setBody(body);
        }
    }
}
