package dev.fedorov.ailife.llmgw.web;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerTest {

    @Autowired
    private WebTestClient client;

    @Test
    void chatRoundTripsThroughMockProvider() {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.user("ping")));

        var response = client.post().uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmChatResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("[default] ping");
    }

    @Test
    void embedReturnsDeterministicVectors() {
        var request = new LlmEmbedRequest(List.of("first", "second"));

        var response = client.post().uri("/v1/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmEmbedResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.vectors()).hasSize(2);
        assertThat(response.vectors().get(0)).hasSize(384);
    }

    @Test
    void streamEmitsServerSentEvents() {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.user("one two")));

        var chunks = client.post().uri("/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        assertThat(String.join("", chunks)).isEqualTo("[default] one two");
    }
}
