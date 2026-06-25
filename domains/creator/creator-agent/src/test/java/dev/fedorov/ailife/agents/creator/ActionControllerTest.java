package dev.fedorov.ailife.agents.creator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the creator's inter-agent action (CR-g) through {@code POST
 * /agents/creator/actions/draft_greeting}: the orchestrator forwards an {@link AgentActionRequest},
 * the {@code greeting-drafter} skill runs one llm-gateway turn, and the action returns
 * {@code {greeting, model}}. A MockWebServer stands in for llm-gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActionControllerTest {

    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient web;
    @Autowired ObjectMapper json;

    @Test
    void draftGreetingRunsTheSkillAndReturnsTheText() throws Exception {
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "С днём рождения, Аня!", "stop", new LlmUsage(30, 12, 42)))));

        var req = new AgentActionRequest("creator", "draft_greeting",
                UUID.randomUUID(), UUID.randomUUID(), "calendar",
                json.createObjectNode().put("person", "Аня").put("occasion", "birthday"));

        AgentActionResult result = post("draft_greeting", req);
        assertThat(result).isNotNull();
        assertThat(result.ok()).isTrue();
        assertThat(result.result().get("greeting").asText()).isEqualTo("С днём рождения, Аня!");
        assertThat(result.result().get("model").asText()).isEqualTo("mock-large");

        // The skill body + the person/occasion payload reached the LLM.
        RecordedRequest llmReq = llmGateway.takeRequest(3, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("greeting-drafter").contains("Аня").contains("birthday");
    }

    @Test
    void missingPersonIsAStructuredError() {
        var req = new AgentActionRequest("creator", "draft_greeting",
                UUID.randomUUID(), UUID.randomUUID(), "calendar",
                json.createObjectNode().put("occasion", "birthday"));

        AgentActionResult result = post("draft_greeting", req);
        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("args.person");
    }

    @Test
    void unknownActionIsRejected() {
        var req = new AgentActionRequest("creator", "do_something",
                UUID.randomUUID(), UUID.randomUUID(), "calendar", null);

        AgentActionResult result = post("do_something", req);
        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("unknown action");
    }

    private AgentActionResult post(String action, AgentActionRequest req) {
        return web.post().uri("/agents/creator/actions/" + action)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(AgentActionResult.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
