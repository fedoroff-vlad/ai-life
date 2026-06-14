package dev.fedorov.ailife.agents.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
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
 * Inbound E2E closer — proves the **user-message → tasks-agent → intent skill** path runs through the
 * agent's <b>real</b> code across HTTP boundaries, not just per-seam mocks. Complements the
 * scheduler-wake closers ({@code E2EStage{1,2,3}…WakeFlowTest}) which cover the proactive direction.
 *
 * <p>Chain exercised (all real tasks-agent beans): {@code POST /agents/tasks/intent}
 * (NormalizedMessage) → {@code IntentController} → {@code IntentRouter} (classifier turn on
 * llm-gateway; MCP tools disabled in tests, but the loaded intent skills still build the classifier)
 * → routes to {@code inbox-clarify} → {@code InboxClarifier} fetches the inbox via mcp-tasks
 * {@code /internal/review} → second LLM turn composes the proposal → {@code IntentResponse}.
 *
 * <p>Two MockWebServers stand in for the only outbound hops on this path — llm-gateway (2 turns:
 * classify, then skill) and mcp-tasks ({@code /internal/review}). The {@code libs/contracts} DTOs
 * (NormalizedMessage in, WeeklyReviewResult over the wire, IntentResponse out) are the bridges being
 * asserted; a serialisation or routing regression on the inbound path fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2EInboundClarifyFlowTest {

    static MockWebServer llmGateway;
    static MockWebServer mcpTasks;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        mcpTasks = new MockWebServer();
        llmGateway.start();
        mcpTasks.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        mcpTasks.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("tasks-agent.mcp-tasks-url", () -> "http://localhost:" + mcpTasks.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void userMessageRoutesToInboxClarifySkillAndReturnsProposal() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        // LLM turn 1 = the IntentRouter classifier → route to the inbox-clarify intent skill.
        llmGateway.enqueue(jsonBody(chatResponse("mock-fast",
                "{\"action\":\"skill\",\"name\":\"inbox-clarify\"}")));
        // mcp-tasks /internal/review → the inbox the skill clarifies.
        var review = new WeeklyReviewResult(household, 1, 0, 0,
                List.of(inboxItem(household, "купить молоко")), List.of(), List.of());
        mcpTasks.enqueue(jsonBody(json.writeValueAsString(review)));
        // LLM turn 2 = the inbox-clarify skill composing the proposal.
        llmGateway.enqueue(jsonBody(chatResponse("mock-large",
                "«купить молоко» → next-action, контекст @errand. Подтвердите — применю.")));

        var msg = new NormalizedMessage(user, household, MessageScope.PRIVATE,
                "разбери мой инбокс", List.of(), "telegram", "99", Instant.now());

        IntentResponse response = http.post().uri("/agents/tasks/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.agent()).isEqualTo("tasks");
        assertThat(response.text()).contains("купить молоко").contains("@errand");
        assertThat(response.llmModel()).isEqualTo("mock-large"); // the skill turn's model

        // Hop A: classifier turn carried the user's text.
        RecordedRequest classify = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(classify.getPath()).isEqualTo("/v1/chat");
        assertThat(classify.getBody().readUtf8()).contains("разбери мой инбокс");

        // Hop B: the skill fetched the inbox from mcp-tasks scoped to the household.
        RecordedRequest reviewCall = mcpTasks.takeRequest(2, TimeUnit.SECONDS);
        assertThat(reviewCall.getMethod()).isEqualTo("GET");
        assertThat(reviewCall.getPath())
                .startsWith("/internal/review")
                .contains("householdId=" + household);

        // Hop C: the skill turn carried the inbox item the LLM proposed against.
        RecordedRequest skillCall = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(skillCall.getBody().readUtf8()).contains("купить молоко");
    }

    private static TaskItemDto inboxItem(UUID household, String title) {
        return new TaskItemDto(UUID.randomUUID(), household, null, null, title, "inbox",
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }

    private String chatResponse(String model, String content) throws Exception {
        return json.writeValueAsString(
                new LlmChatResponse(model, content, "stop", new LlmUsage(10, 5, 15)));
    }

    private static MockResponse jsonBody(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
