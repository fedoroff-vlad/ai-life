package dev.fedorov.ailife.agents.calendar;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code create_event} inter-agent action (Stage 4 / C1c): maps the invoke
 * {@code args} to a {@code CreateEventInput} and persists it via mcp-caldav's
 * {@code /internal/event}, returning {@code {eventUid}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActionControllerTest {

    static MockWebServer llmGateway;
    static MockWebServer mcpCaldav;
    static MockWebServer profile;

    @BeforeAll
    static void startMocks() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        mcpCaldav = new MockWebServer();
        mcpCaldav.start();
        profile = new MockWebServer();
        profile.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        llmGateway.shutdown();
        mcpCaldav.shutdown();
        profile.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("calendar-agent.mcp-caldav-url", () -> "http://localhost:" + mcpCaldav.getPort());
        r.add("calendar-agent.profile-service-url", () -> "http://localhost:" + profile.getPort());
        // Item 8: the SharingResolver now consults/records the learned-decision tally on memory-service.
        // These cases exercise the no-history path (static policy), so point it at a fast-fail address —
        // the learned lookup soft-fails to empty (→ static default) and the explicit-choice record is
        // swallowed, both without a real memory-service. Behaviour stays exactly as before item 8.
        r.add("calendar-agent.memory-service-url", () -> "http://127.0.0.1:1");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void createEventMapsArgsAndReturnsEventUid() throws Exception {
        UUID household = UUID.randomUUID();
        var dto = new CalendarEventDto(
                UUID.randomUUID(), household, "ours", "cal-uid-1", "Pay rent",
                null, null, Instant.parse("2026-07-01T09:00:00Z"), null, null, List.of(), null);
        mcpCaldav.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(dto)));

        var args = json.createObjectNode()
                .put("summary", "Pay rent")
                .put("dtstart", "2026-07-01T09:00:00Z");
        var req = new AgentActionRequest("calendar", "create_event", household, null, "tasks", args);

        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("eventUid").asString()).isEqualTo("cal-uid-1");
                });

        RecordedRequest sent = mcpCaldav.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/internal/event");
        assertThat(sent.getBody().readUtf8())
                .contains("\"summary\":\"Pay rent\"")
                .contains("\"householdId\":\"" + household + "\"");
    }

    @Test
    void sharedChoiceRoutesToFamilyHousehold() throws Exception {
        UUID user = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        enqueueRouting(personal, List.of(family));
        enqueueCaldav(family, "uid-shared");

        var args = json.createObjectNode()
                .put("summary", "Family dinner")
                .put("dtstart", "2026-07-01T18:00:00Z")
                .put("sharing", "SHARED");
        var req = new AgentActionRequest("calendar", "create_event", personal, user, "tasks", args);

        postOkWithUid(req, "uid-shared");

        assertThat(profile.takeRequest().getPath())
                .isEqualTo("/v1/users/" + user + "/household-routing");
        assertThat(mcpCaldav.takeRequest().getBody().readUtf8())
                .contains("\"householdId\":\"" + family + "\"")
                .contains("\"sharing\":\"SHARED\"");
    }

    @Test
    void birthdayCategoryDefaultsToSharedHousehold() throws Exception {
        UUID user = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        enqueueRouting(personal, List.of(family));
        enqueueCaldav(family, "uid-bday");

        // No explicit sharing → the occasion policy defaults a birthday to the shared household.
        var args = json.createObjectNode()
                .put("summary", "Маша birthday")
                .put("dtstart", "2026-08-01T00:00:00Z");
        args.putArray("categories").add("birthday");
        var req = new AgentActionRequest("calendar", "create_event", personal, user, "tasks", args);

        postOkWithUid(req, "uid-bday");

        profile.takeRequest();
        assertThat(mcpCaldav.takeRequest().getBody().readUtf8())
                .contains("\"householdId\":\"" + family + "\"");
    }

    @Test
    void plainEventDefaultsToPersonalHousehold() throws Exception {
        UUID user = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        enqueueRouting(personal, List.of(family));
        enqueueCaldav(personal, "uid-priv");

        // No occasion, no explicit sharing → private → the author's personal household.
        var args = json.createObjectNode()
                .put("summary", "Dentist")
                .put("dtstart", "2026-09-01T09:00:00Z");
        var req = new AgentActionRequest("calendar", "create_event", personal, user, "tasks", args);

        postOkWithUid(req, "uid-priv");

        profile.takeRequest();
        assertThat(mcpCaldav.takeRequest().getBody().readUtf8())
                .contains("\"householdId\":\"" + personal + "\"");
    }

    @Test
    void sharedChoiceWithoutFamilyDegradesToPersonal() throws Exception {
        UUID user = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        enqueueRouting(personal, List.of());
        enqueueCaldav(personal, "uid-degrade");

        var args = json.createObjectNode()
                .put("summary", "Solo trip")
                .put("dtstart", "2026-10-01T09:00:00Z")
                .put("sharing", "SHARED");
        var req = new AgentActionRequest("calendar", "create_event", personal, user, "tasks", args);

        postOkWithUid(req, "uid-degrade");

        profile.takeRequest();
        assertThat(mcpCaldav.takeRequest().getBody().readUtf8())
                .contains("\"householdId\":\"" + personal + "\"");
    }

    private void enqueueRouting(UUID personal, List<UUID> shared) throws Exception {
        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new HouseholdRoutingDto(personal, shared))));
    }

    private void enqueueCaldav(UUID household, String uid) throws Exception {
        var dto = new CalendarEventDto(
                UUID.randomUUID(), household, "ours", uid, "evt",
                null, null, Instant.parse("2026-07-01T09:00:00Z"), null, null, List.of(), null);
        mcpCaldav.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(dto)));
    }

    private void postOkWithUid(AgentActionRequest req, String expectedUid) {
        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("eventUid").asString()).isEqualTo(expectedUid);
                });
    }

    @Test
    void endBeforeStartIsRejected() {
        // Sanity spot-check (#485): an event that ends at-or-before it starts is impossible → ok=false,
        // rejected before any mcp-caldav call (no response enqueued).
        var args = json.createObjectNode()
                .put("summary", "Broken slot")
                .put("dtstart", "2026-07-01T15:00:00Z")
                .put("dtend", "2026-07-01T14:00:00Z");
        var req = new AgentActionRequest("calendar", "create_event", UUID.randomUUID(), null, "tasks", args);

        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("end must be after start");
                });
    }

    @Test
    void overlappingEventIsFlaggedAsADoubleBookingWarning() throws Exception {
        UUID household = UUID.randomUUID();
        // 1) the create returns the new event, 2) the double-booking read returns an overlapping one.
        var created = new CalendarEventDto(
                UUID.randomUUID(), household, "ours", "uid-new", "Team sync",
                null, null, Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-01T11:00:00Z"), null, List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(created)));
        var existing = new CalendarEventDto(
                UUID.randomUUID(), household, "ours", "uid-existing", "Стоматолог",
                null, null, Instant.parse("2026-07-01T10:30:00Z"),
                Instant.parse("2026-07-01T11:15:00Z"), null, List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(existing))));

        var args = json.createObjectNode()
                .put("summary", "Team sync")
                .put("dtstart", "2026-07-01T10:00:00Z")
                .put("dtend", "2026-07-01T11:00:00Z");
        // userId null → no profile-routing call; the envelope household is used directly.
        var req = new AgentActionRequest("calendar", "create_event", household, null, "tasks", args);

        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("eventUid").asString()).isEqualTo("uid-new");
                    assertThat(res.result().get("warning").asString())
                            .contains("⚠️").contains("пересекается").contains("Стоматолог");
                });

        assertThat(mcpCaldav.takeRequest().getPath()).isEqualTo("/internal/event");        // create
        assertThat(mcpCaldav.takeRequest().getPath()).startsWith("/internal/events");       // overlap read
    }

    @Test
    void unknownActionReturnsErrorResult() {
        var req = new AgentActionRequest("calendar", "frobnicate", UUID.randomUUID(), null, "tasks", null);
        http.post().uri("/agents/calendar/actions/frobnicate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("unknown action");
                });
    }

    @Test
    void missingRequiredFieldsReturnsErrorResult() {
        // args has summary but no dtstart → validation rejects before any mcp-caldav call
        // (no response enqueued — a stray call would surface as a different error message).
        var args = json.createObjectNode().put("summary", "Pay rent");
        var req = new AgentActionRequest("calendar", "create_event", UUID.randomUUID(), null, "tasks", args);

        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("dtstart");
                });
    }
}
