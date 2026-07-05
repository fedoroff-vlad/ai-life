package dev.fedorov.ailife.agents.notes;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.notes.config.NotesAgentProperties;
import dev.fedorov.ailife.agents.notes.http.SchedulerClient;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-c: {@link SchedulerClient#ensureResurfaceSchedule} is idempotent — it registers a household
 * {@code notes.resurface} cron only when none exists yet, so firing it on every note capture never
 * piles up duplicate schedules. MockWebServer stands in for scheduler-service.
 */
class SchedulerClientTest {

    private MockWebServer scheduler;
    private SchedulerClient client;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new MockWebServer();
        scheduler.start();
        NotesAgentProperties props = new NotesAgentProperties();
        props.setResurfaceCron("0 0 10 * * MON");
        WebClient web = WebClient.builder().baseUrl("http://localhost:" + scheduler.getPort()).build();
        client = new SchedulerClient(web, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown();
    }

    @Test
    void registersAResurfaceCronWhenTheHouseholdHasNone() throws Exception {
        UUID household = UUID.randomUUID();
        scheduler.enqueue(jsonResponse("[]"));                                   // list → nothing yet
        scheduler.enqueue(jsonResponse(json.writeValueAsString(schedule(household))));   // create

        client.ensureResurfaceSchedule(household).block();

        RecordedRequest listReq = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(listReq.getPath()).startsWith("/v1/schedules").contains("householdId=" + household);
        assertThat(listReq.getMethod()).isEqualTo("GET");

        RecordedRequest createReq = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(createReq.getMethod()).isEqualTo("POST");
        assertThat(createReq.getPath()).isEqualTo("/v1/schedules");
        String body = createReq.getBody().readUtf8();
        assertThat(body)
                .contains("\"kind\":\"notes.resurface\"")
                .contains("\"ownerAgent\":\"notes\"")
                .contains(household.toString())
                .contains("0 0 10 * * MON");
    }

    @Test
    void doesNotRegisterWhenAResurfaceCronAlreadyExists() throws Exception {
        UUID household = UUID.randomUUID();
        scheduler.enqueue(jsonResponse(json.writeValueAsString(List.of(schedule(household)))));  // already there

        client.ensureResurfaceSchedule(household).block();

        RecordedRequest listReq = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(listReq.getMethod()).isEqualTo("GET");
        // No POST follows — the existing cron is left alone.
        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void softFailsWhenSchedulerErrors() throws Exception {
        UUID household = UUID.randomUUID();
        scheduler.enqueue(new MockResponse().setResponseCode(500));

        // No throw — a scheduler outage must not fault the note capture that fired this.
        client.ensureResurfaceSchedule(household).block();

        assertThat(scheduler.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    }

    private ScheduleDto schedule(UUID household) {
        return new ScheduleDto(UUID.randomUUID(), household, "notes", "notes.resurface",
                "0 0 10 * * MON", null, true, Instant.now().plusSeconds(3600), null, Instant.now());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
