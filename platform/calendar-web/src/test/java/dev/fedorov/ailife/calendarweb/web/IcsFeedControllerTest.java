package dev.fedorov.ailife.calendarweb.web;

import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ICS feed end-to-end with mcp-caldav mocked. Covers both token sources (track B): the persistent
 * store ({@code /internal/feeds/{token}}) and the env-configured fallback, plus an unknown token → 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class IcsFeedControllerTest {

    static MockWebServer caldav;
    static MockWebServer profile;
    static final UUID HOUSEHOLD = UUID.randomUUID();
    static final UUID FAMILY = UUID.randomUUID();
    static final UUID OWNER = UUID.randomUUID();
    static final String DB_TOKEN = "db-minted-token-aaa";
    static final String ENV_TOKEN = "env-static-token-bbb";
    static final String OWNER_TOKEN = "db-owner-token-ccc";
    static final ObjectMapper M = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        caldav = new MockWebServer();
        caldav.setDispatcher(new CaldavDispatcher());
        caldav.start();
        profile = new MockWebServer();
        profile.setDispatcher(new ProfileDispatcher());
        profile.start();
    }

    @AfterAll
    static void stop() throws Exception {
        caldav.shutdown();
        profile.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("calendar-web.mcp-caldav-url", () -> "http://localhost:" + caldav.getPort());
        r.add("calendar-web.profile-service-url", () -> "http://localhost:" + profile.getPort());
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
                .contains("DTSTART:20260715T080000Z")
                .doesNotContain("SUMMARY:Family dinner");  // owner-less feed → single household only
    }

    @Test
    void perPersonFeedServesOwnAndSharedHouseholds() {
        // A feed with an ownerId → calendar-web resolves the member's household set (personal ∪ shared)
        // from profile-service and reads the union, so shared events surface too (ADR-0001 slice 5 / #295).
        String body = http.get().uri("/ics/" + OWNER_TOKEN + ".ics")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/calendar")
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(body)
                .contains("SUMMARY:ДР Маши")        // own (personal household)
                .contains("SUMMARY:Family dinner"); // shared (family household, via the resolved set)
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
                if (path.startsWith("/internal/feeds/" + OWNER_TOKEN)) {
                    var feed = new CalendarFeedDto(UUID.randomUUID(), HOUSEHOLD, OWNER, "Vlad",
                            OWNER_TOKEN, Instant.now(), null);
                    return json(M.writeValueAsString(feed));
                }
                if (path.startsWith("/internal/feeds/")) {
                    return new MockResponse().setResponseCode(404);   // unknown/revoked → env fallback
                }
                if (path.startsWith("/internal/events")) {
                    var events = new java.util.ArrayList<CalendarEventDto>();
                    events.add(new CalendarEventDto(
                            UUID.randomUUID(), HOUSEHOLD, "ours", "uid-xyz", "ДР Маши", null, null,
                            Instant.parse("2026-07-15T08:00:00Z"), Instant.parse("2026-07-15T09:00:00Z"),
                            "FREQ=YEARLY", List.of("birthday"), null));
                    // The union request carries the family household too → include its shared event.
                    if (path.contains(FAMILY.toString())) {
                        events.add(new CalendarEventDto(
                                UUID.randomUUID(), FAMILY, "ours", "uid-fam", "Family dinner", null, null,
                                Instant.parse("2026-07-16T18:00:00Z"), Instant.parse("2026-07-16T19:00:00Z"),
                                null, List.of(), null));
                    }
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

    /** Serves the owner → household-set resolution (personal ∪ shared) for the per-person feed. */
    static final class ProfileDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.startsWith("/v1/users/" + OWNER + "/households")) {
                    return new MockResponse().setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(List.of(HOUSEHOLD, FAMILY)));
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }
    }
}
