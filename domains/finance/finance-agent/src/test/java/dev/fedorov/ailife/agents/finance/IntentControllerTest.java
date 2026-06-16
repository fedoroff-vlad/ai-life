package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
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
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void intentRoutesUserMessageToLlmWithManifestSystemPrompt() throws Exception {
        var fakeLlm = new LlmChatResponse(
                "mock-large", "Logged: -12 EUR on coffee (uncategorised).", "stop",
                new LlmUsage(20, 10, 30));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(fakeLlm)));

        var msg = new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "Потратил 12 евро на кофе",
                List.of(), "telegram", "42", Instant.now());

        http.post().uri("/agents/finance/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> {
                    assertThat(r.agent()).isEqualTo("finance");
                    assertThat(r.text()).contains("coffee");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                });

        RecordedRequest llmReq = llmGateway.takeRequest();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("finance agent");        // AGENT.md body became system prompt
        assertThat(body).contains("евро на кофе");          // user text passed through
    }
}
