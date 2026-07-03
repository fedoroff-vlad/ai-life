package dev.fedorov.ailife.agents.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-b: the {@code notes.resurface} proactive wake receiver. A scheduler-shaped wake →
 * {@code TriggerController} → {@code NoteResurfacer} pulls one stale note from memory-service
 * ({@code GET /v1/notes/resurface}) and delivers a reminder via notifier-service. MockWebServers stand
 * in for memory-service and notifier. A {@code 204} (nothing stale) delivers nothing; an unbound kind is
 * 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerResurfaceTest {

    static MockWebServer memoryService;
    static MockWebServer notifier;

    @BeforeAll
    static void start() throws Exception {
        memoryService = new MockWebServer();
        notifier = new MockWebServer();
        memoryService.start();
        notifier.start();
    }

    @AfterAll
    static void stop() throws Exception {
        memoryService.shutdown();
        notifier.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("notes-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("notes-agent.notifier-url", () -> "http://localhost:" + notifier.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void resurfaceWakeSurfacesAStaleNoteToItsOwner() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        NoteDto stale = new NoteDto(UUID.randomUUID(), householdId, ownerId, "Мама — что любит",
                "person", List.of("подарок"), "user", null,
                "Любит пионы в горшке, не срезку.", null, Instant.now(), Instant.now());
        memoryService.enqueue(jsonResponse(json.writeValueAsString(stale)));
        notifier.enqueue(new MockResponse().setResponseCode(202));

        wake(householdId, "notes.resurface");

        // memory-service was asked for a resurfacing candidate with the configured staleness window.
        RecordedRequest resurfaceReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(resurfaceReq.getPath())
                .startsWith("/v1/notes/resurface")
                .contains("householdId=" + householdId)
                .contains("olderThanDays=30");

        // The owner got a reminder naming the note.
        RecordedRequest notifyReq = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(notifyReq.getPath()).isEqualTo("/v1/notify");
        String body = notifyReq.getBody().readUtf8();
        assertThat(body)
                .contains(ownerId.toString())
                .contains("Мама — что любит")
                .contains("не срезку");
    }

    @Test
    void nothingStaleDeliversNoReminder() throws Exception {
        UUID householdId = UUID.randomUUID();
        memoryService.enqueue(new MockResponse().setResponseCode(204));   // nothing stale

        wake(householdId, "notes.resurface");

        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/v1/notes/resurface");
        // No notifier call — the wake is a silent no-op on a quiet week.
        assertThat(notifier.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unknownTriggerKindReturns404() {
        var wake = new AgentWakeRequest(UUID.randomUUID(), UUID.randomUUID(), "notes", "no.such.kind", null);
        http.post().uri("/agents/notes/triggers/no.such.kind")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(wake)
                .exchange().expectStatus().isNotFound();
    }

    private void wake(UUID householdId, String kind) {
        var wake = new AgentWakeRequest(UUID.randomUUID(), householdId, "notes", kind, null);
        http.post().uri("/agents/notes/triggers/" + kind)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(wake)
                .exchange().expectStatus().isAccepted();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
