package dev.fedorov.ailife.agents.finance;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E for the generic {@code brief} read-action (#290, Slice B) — the new cross-service wire this slice
 * adds. In ONE real finance-agent Spring context, MockWebServers stand in for memory-service + llm-gateway;
 * a hub-forwarded {@link AgentActionRequest} to {@code POST /agents/finance/actions/brief} recalls from the
 * second brain, synthesizes one FAST answer, and returns a grounded {@link AgentActionResult} — the shape
 * the coordinator (Slice B2) will fold into a multi-domain synthesis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        r.add("finance-agent.memory-service-url", () -> "http://localhost:" + memory.getPort());
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
                        "Owner overspends on dining out near month-end", null, Instant.now()), 0.13)))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Твоё слабое место — рестораны к концу месяца.", "stop",
                new LlmUsage(90, 30, 120)))));

        ObjectNode args = json.createObjectNode();
        args.put("question", "что важно по финансам при планировании недели?");
        AgentActionRequest req = new AgentActionRequest("finance", "brief", household, user, "coordinator", args);

        http.post().uri("/agents/finance/actions/brief")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("agent").asText()).isEqualTo("finance");
                    assertThat(res.result().get("answer").asText()).isEqualTo("Твоё слабое место — рестораны к концу месяца.");
                });

        // Hop 1: recall carried the household + question.
        RecordedRequest recallReq = memory.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq.getPath()).isEqualTo("/v1/memories/recall");
        JsonNode recallBody = json.readTree(recallReq.getBody().readUtf8());
        assertThat(recallBody.path("householdId").asText()).isEqualTo(household.toString());
        assertThat(recallBody.path("query").asText()).contains("планировании недели");

        // Hop 2: the FAST synthesis carried the recalled fact (grounding).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).contains("overspends on dining out").contains("\"channel\":\"fast\"");
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
