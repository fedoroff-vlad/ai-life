package dev.fedorov.ailife.agents.tasks;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real {@code weekly.review} trigger flow: enrich via mcp-tasks {@code /internal/review} → LLM
 * (AGENT.md + SKILL.md) → notifier fan-out to every household member. Four MockWebServers stand in
 * for llm-gateway / mcp-tasks / profile-service / notifier-service. Mirrors finance's
 * TriggerControllerTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TriggerControllerTest {

    static MockWebServer llmGateway;
    static MockWebServer mcpTasks;
    static MockWebServer profileService;
    static MockWebServer notifierService;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        mcpTasks = new MockWebServer();
        profileService = new MockWebServer();
        notifierService = new MockWebServer();
        llmGateway.start();
        mcpTasks.start();
        profileService.start();
        notifierService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        mcpTasks.shutdown();
        profileService.shutdown();
        notifierService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("tasks-agent.mcp-tasks-url", () -> "http://localhost:" + mcpTasks.getPort());
        r.add("tasks-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("tasks-agent.notifier-url", () -> "http://localhost:" + notifierService.getPort());
    }

    @Autowired WebTestClient http;

    @BeforeEach
    void drain() throws Exception {
        for (MockWebServer s : new MockWebServer[]{llmGateway, mcpTasks, profileService, notifierService}) {
            while (s.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
                // discard leftovers — SpringBootTest reuses the context across methods
            }
        }
    }

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private String twoUsers() {
        return "[{\"id\":\"" + ALICE + "\",\"householdId\":\"" + HOUSEHOLD + "\",\"displayName\":\"Alice\"},"
                + "{\"id\":\"" + BOB + "\",\"householdId\":\"" + HOUSEHOLD + "\",\"displayName\":\"Bob\"}]";
    }

    private String reviewAggregate() {
        return "{\"householdId\":\"" + HOUSEHOLD + "\",\"inboxCount\":2,\"waitingCount\":0,"
                + "\"stuckProjectCount\":1,\"inbox\":[{\"title\":\"молоко\"}],\"waiting\":[],"
                + "\"stuckProjects\":[{\"name\":\"Ремонт\"}]}";
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private AgentWakeRequest wake() {
        return new AgentWakeRequest(UUID.randomUUID(), HOUSEHOLD, "tasks", "weekly.review", null);
    }

    @Test
    void weeklyReviewEnrichesFromMcpTasksThenFansOutToHousehold() throws Exception {
        mcpTasks.enqueue(json(reviewAggregate()));
        llmGateway.enqueue(json("{\"model\":\"mock-large\",\"content\":\"В инбоксе 2 задачи, проект «Ремонт» без следующего шага.\",\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":10,\"completionTokens\":5,\"totalTokens\":15}}"));
        profileService.enqueue(json(twoUsers()));
        notifierService.enqueue(new MockResponse().setResponseCode(202));
        notifierService.enqueue(new MockResponse().setResponseCode(202));

        http.post().uri("/agents/tasks/triggers/weekly.review")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake())
                .exchange()
                .expectStatus().isAccepted();

        // Enrichment hit /internal/review with the household.
        RecordedRequest reviewCall = mcpTasks.takeRequest(2, TimeUnit.SECONDS);
        assertThat(reviewCall).isNotNull();
        assertThat(reviewCall.getPath()).contains("/internal/review");
        assertThat(reviewCall.getPath()).contains(HOUSEHOLD.toString());

        // LLM got the enriched payload (counts) layered under the skill.
        RecordedRequest llmCall = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        String llmBody = llmCall.getBody().readUtf8();
        assertThat(llmBody).contains("inboxCount");
        assertThat(llmBody).contains("weekly-review"); // SKILL.md body name

        // Fan-out: both household members notified.
        assertThat(profileService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        RecordedRequest n1 = notifierService.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest n2 = notifierService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(n1).isNotNull();
        assertThat(n2).isNotNull();
        assertThat(n1.getBody().readUtf8() + n2.getBody().readUtf8())
                .contains(ALICE.toString()).contains(BOB.toString());
    }

    @Test
    void skipSentinelSuppressesNotification() throws Exception {
        mcpTasks.enqueue(json(reviewAggregate()));
        llmGateway.enqueue(json("{\"model\":\"mock-large\",\"content\":\"SKIP\",\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":1,\"completionTokens\":1,\"totalTokens\":2}}"));

        http.post().uri("/agents/tasks/triggers/weekly.review")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake())
                .exchange()
                .expectStatus().isAccepted();

        // LLM ran, but SKIP short-circuited before profile/notifier.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(profileService.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unknownTriggerKind404sWithoutTouchingLlm() throws Exception {
        http.post().uri("/agents/tasks/triggers/nope.kind")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AgentWakeRequest(UUID.randomUUID(), HOUSEHOLD, "tasks", "nope.kind", null))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(llmGateway.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
    }
}
