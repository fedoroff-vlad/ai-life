package dev.fedorov.ailife.agents.tasks.capture;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactive {@code task-capture} flow (ADR-0002 slice 5 — the tasks domain's sharing WRITE path).
 * {@code TaskCapturer} asks the LLM for a plan, lets the shared {@code SharingResolver} route the task to a
 * concrete household (personal for a private todo, the family household for a shared/household one), then
 * captures it via mcp-tasks' {@code POST /internal/task}. Three MockWebServers stand in for mcp-tasks /
 * llm-gateway / profile-service. Mirrors finance's {@code AccountManagerTest}.
 */
@SpringBootTest
class TaskCapturerTest {

    static MockWebServer mcpTasks;
    static MockWebServer llmGateway;
    static MockWebServer profileService;

    @BeforeAll
    static void start() throws Exception {
        mcpTasks = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        mcpTasks.start();
        llmGateway.start();
        profileService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpTasks.shutdown();
        llmGateway.shutdown();
        profileService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("tasks-agent.mcp-tasks-url", () -> "http://localhost:" + mcpTasks.getPort());
        r.add("tasks-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        // Item 8: the SharingResolver now consults/records the learned-decision tally on memory-service.
        // These cases exercise the no-history path (static policy), so point it at a fast-fail address — the
        // learned lookup soft-fails to empty (→ static default) and any record is swallowed. Behaviour is
        // exactly as before item 8.
        r.add("tasks-agent.memory-service-url", () -> "http://127.0.0.1:1");
    }

    @Autowired TaskCapturer capturer;
    @Autowired dev.fedorov.ailife.sharing.SharingConfirm sharingConfirm;
    @Autowired ObjectMapper json;

    @Test
    void personalTaskRoutesToPersonalHousehold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();
        UUID envelopeHh = UUID.randomUUID(); // the message arrived in some household — must be overridden

        // Plan: a personal todo (shared=false).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"title\":\"позвонить врачу\",\"shared\":false}",
                "stop", new LlmUsage(20, 15, 35)))));
        // household-routing: the user's personal + one shared household.
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        // The captured task echoes back.
        mcpTasks.enqueue(jsonResponse(json.writeValueAsString(inboxItem(personalHh, "позвонить врачу"))));

        var msg = new NormalizedMessage(userId, envelopeHh, MessageScope.PRIVATE,
                "напомни позвонить врачу", List.of(), "telegram", "1", Instant.now());

        TaskCapturer.CaptureResult result = capturer.capture(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("позвонить врачу").doesNotContain("общий список");
        assertThat(result.model()).isEqualTo("mock-large");

        // Routing split was read for the acting user.
        RecordedRequest routing = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(routing.getPath()).contains("/household-routing").contains(userId.toString());

        // The task was captured under the PERSONAL household, not the envelope one.
        RecordedRequest post = mcpTasks.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getPath()).isEqualTo("/internal/task");
        String body = post.getBody().readUtf8();
        assertThat(body).contains(personalHh.toString())
                .doesNotContain(envelopeHh.toString())
                .contains("позвонить врачу");
    }

    @Test
    void sharedTaskRoutesToSharedHousehold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();

        // Plan: a household chore (shared=true).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"title\":\"вынести мусор\",\"shared\":true}",
                "stop", new LlmUsage(20, 15, 35)))));
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        mcpTasks.enqueue(jsonResponse(json.writeValueAsString(inboxItem(sharedHh, "вынести мусор"))));

        var msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "нужно вынести мусор — это общее", List.of(), "telegram", "2", Instant.now());

        TaskCapturer.CaptureResult result = capturer.capture(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("общий список").contains("вынести мусор");

        profileService.takeRequest(2, TimeUnit.SECONDS);
        // A shared task lands in the SHARED household.
        RecordedRequest post = mcpTasks.takeRequest(2, TimeUnit.SECONDS);
        String body = post.getBody().readUtf8();
        assertThat(body).contains(sharedHh.toString()).doesNotContain(personalHh.toString());
    }

    @Test
    void emptyPlanAsksInsteadOfCapturing() throws Exception {
        // The LLM sees nothing to capture → replies {} → the flow asks, and does NOT capture.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{}", "stop", new LlmUsage(20, 15, 35)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "спасибо", List.of(), "telegram", "3", Instant.now());

        TaskCapturer.CaptureResult result = capturer.capture(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Не понял");
        // No task captured — mcp-tasks was never called.
        assertThat(mcpTasks.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void ambiguousCaptureAsksInsteadOfCapturing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();

        // Plan with NO `shared` field → the LLM couldn't tell → DS-N ambiguity → the agent must ask.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"title\":\"забронировать столик\"}", "stop", new LlmUsage(20, 15, 35)))));
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));

        var msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "забронировать столик", List.of(), "telegram", "amb-1", Instant.now());

        TaskCapturer.CaptureResult result = capturer.capture(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("личное или общее").contains("забронировать столик");
        // It deferred: a pendingAction is returned (→ orchestrator locks), and NOTHING was captured yet.
        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().path("flow").asString()).isEqualTo("sharing-confirm");
        assertThat(mcpTasks.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void resumeAfterAnswerCapturesIntoTheChosenHousehold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();

        // 1) Ambiguous capture → ask (pendingAction carries the routing the resume needs).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"title\":\"вынести мусор\"}", "stop", new LlmUsage(20, 15, 35)))));
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        var msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "вынести мусор", List.of(), "telegram", "amb-2", Instant.now());
        TaskCapturer.CaptureResult asked = capturer.capture(msg).block();
        assertThat(asked).isNotNull();
        assertThat(asked.pendingAction()).isNotNull();

        // 2) The owner answers «это общее» → the resolver picks the shared household + the task is captured.
        mcpTasks.enqueue(jsonResponse(json.writeValueAsString(inboxItem(sharedHh, "вынести мусор"))));
        String reply = sharingConfirm.resume(asked.pendingAction(), "это общее",
                capturer::finishCapture).block().text();

        assertThat(reply).contains("общий список").contains("вынести мусор");
        RecordedRequest post = mcpTasks.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post.getPath()).isEqualTo("/internal/task");
        assertThat(post.getBody().readUtf8()).contains(sharedHh.toString()).doesNotContain(personalHh.toString());
    }

    private static TaskItemDto inboxItem(UUID household, String title) {
        return new TaskItemDto(UUID.randomUUID(), household, null, null, title, "inbox",
                null, null, null, null, null, "telegram", null, null, null, Instant.now(), null);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
