package dev.fedorov.ailife.agents.calendar;

import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
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
    void nonEventMessageFallsThroughToChatWithManifestSystemPrompt() throws Exception {
        // The in-agent router (#475 / Track H.2) now classifies first: a read question routes to `chat`,
        // then CalendarChat makes the real reply call. Two llm-gateway turns.
        llmGateway.enqueue(chatResponse("{\"action\":\"chat\",\"text\":\"...\"}"));   // routing → chat
        llmGateway.enqueue(chatResponse("Maria's birthday is on May 5."));            // CalendarChat reply

        var msg = new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "Когда у Маши день рождения?",
                List.of(), "telegram", "42", Instant.now());

        http.post().uri("/agents/calendar/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> {
                    assertThat(r.agent()).isEqualTo("calendar");
                    assertThat(r.text()).contains("May 5");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                });

        RecordedRequest routing = llmGateway.takeRequest();          // classifier turn
        assertThat(routing.getPath()).isEqualTo("/v1/chat");
        assertThat(routing.getBody().readUtf8()).contains("Когда у Маши");   // user text classified
        RecordedRequest chat = llmGateway.takeRequest();             // chat reply turn
        String body = chat.getBody().readUtf8();
        assertThat(body).contains("calendar agent");      // AGENT.md body became system prompt
        assertThat(body).contains("Когда у Маши");        // user text passed through
    }

    private MockResponse chatResponse(String content) throws Exception {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }
}
