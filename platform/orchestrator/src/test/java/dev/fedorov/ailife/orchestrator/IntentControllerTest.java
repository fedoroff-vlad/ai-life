package dev.fedorov.ailife.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.MockResponse;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntentControllerTest {

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
    static void wireLlmClient(DynamicPropertyRegistry registry) {
        registry.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private ObjectMapper json;

    @Test
    void routesToEchoAgentAndReturnsLlmContent() throws Exception {
        var fakeLlm = new LlmChatResponse(
                "mock-large", "hello back", "stop", new LlmUsage(5, 3, 8));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(fakeLlm)));

        var msg = new NormalizedMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MessageScope.PRIVATE,
                "hello",
                List.of(),
                "telegram",
                "42",
                Instant.now());

        var response = client.post().uri("/v1/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.agent()).isEqualTo("echo");
        assertThat(response.text()).isEqualTo("hello back");
        assertThat(response.llmModel()).isEqualTo("mock-large");

        var llmRequest = llmGateway.takeRequest();
        assertThat(llmRequest.getPath()).isEqualTo("/v1/chat");
        assertThat(llmRequest.getBody().readUtf8()).contains("\"hello\"");
    }
}
