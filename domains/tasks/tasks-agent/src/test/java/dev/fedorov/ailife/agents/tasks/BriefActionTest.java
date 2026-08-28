package dev.fedorov.ailife.agents.tasks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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
 * E2E for the generic {@code brief} read-action on tasks-agent (#290, Slice B / #477 Track I — tasks is the
 * third {@code brief} exposer, after finance + calendar). In ONE real tasks-agent Spring context,
 * MockWebServers stand in for memory-service + llm-gateway; a hub-forwarded {@link AgentActionRequest} to
 * {@code POST /agents/tasks/actions/brief} recalls from the second brain, synthesizes one FAST answer, and
 * returns a grounded {@link AgentActionResult} — the shape the coordinator (Slice B2) folds into a
 * multi-domain synthesis so a "спланируй выходные" ask can fan out to ≥3 real specialists.
 *
 * <p>mcp-tasks is disabled here ({@code TASKS_AGENT_MCP_CLIENT_ENABLED=false}) — the {@code brief} path is
 * read-only over memory recall + LLM and needs no tools.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.mcp.client.enabled=false")
@AutoConfigureWebTestClient
class BriefActionTest {

    static MockWebServer memory;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        memory = new MockWebServer();
        llmGateway = new MockWebServer();
        memory.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        memory.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("tasks-agent.memory-service-url", () -> "http://localhost:" + memory.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void briefRecallsThenSynthesizesAGroundedAnswer() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        memory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new RecallMemoryHit(new MemoryDto(UUID.randomUUID(), household, user, null, "note",
                        "Надо продлить страховку на машине до конца месяца", null, Instant.now()), 0.11)))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "На выходных стоит продлить страховку на машине.", "stop",
                new LlmUsage(80, 25, 105)))));

        ObjectNode args = json.createObjectNode();
        args.put("question", "что важного из задач сделать на выходных?");
        AgentActionRequest req = new AgentActionRequest("tasks", "brief", household, user, "coordinator", args);

        http.post().uri("/agents/tasks/actions/brief")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("agent").asString()).isEqualTo("tasks");
                    assertThat(res.result().get("answer").asString())
                            .isEqualTo("На выходных стоит продлить страховку на машине.");
                });

        // Hop 1: recall carried the household + question.
        RecordedRequest recallReq = memory.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq.getPath()).isEqualTo("/v1/memories/recall");
        JsonNode recallBody = json.readTree(recallReq.getBody().readUtf8());
        assertThat(recallBody.path("householdId").asString()).isEqualTo(household.toString());
        assertThat(recallBody.path("query").asString()).contains("задач");

        // Hop 2: the FAST synthesis carried the recalled fact (grounding).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).contains("страховку").contains("\"channel\":\"fast\"");
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
