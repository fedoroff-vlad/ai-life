package dev.fedorov.ailife.orchestrator;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.MockWebServer;
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

import java.util.UUID;

/**
 * EchoAgent is registered under id "echo" — known agent path → 202; otherwise → 404.
 * Real dispatch (calling agent's wake handler) is intentionally out of scope here;
 * see {@code AgentWakeController} javadoc.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "orchestrator.agents=")
@AutoConfigureWebTestClient
class AgentWakeControllerTest {

    static MockWebServer llmGateway;

    @BeforeAll
    static void startMockLlm() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
    }

    @AfterAll
    static void stopMockLlm() throws Exception {
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired
    WebTestClient client;

    @Autowired
    ObjectMapper json;

    @Test
    void knownAgent_returns202() {
        var req = new AgentWakeRequest(
                UUID.randomUUID(), UUID.randomUUID(), "echo", "ping",
                json.createObjectNode().put("note", "hi"));
        client.post().uri("/v1/agents/wake")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void unknownAgent_returns404() {
        var req = new AgentWakeRequest(
                UUID.randomUUID(), UUID.randomUUID(), "no-such-agent", "ping", null);
        client.post().uri("/v1/agents/wake")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isNotFound();
    }
}
