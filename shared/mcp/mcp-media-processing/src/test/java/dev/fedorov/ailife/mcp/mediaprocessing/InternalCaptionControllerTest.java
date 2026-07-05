package dev.fedorov.ailife.mcp.mediaprocessing;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionInput;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-c1: the {@code POST /internal/caption} HTTP passthrough drives the same fetch →
 * vision-channel logic as the MCP {@code caption} tool, but over a MockWebServer-testable
 * transport an agent can call deterministically (the MCP/SSE transport can't be mocked).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalCaptionControllerTest {

    static MockWebServer mediaService;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mediaService = new MockWebServer();
        mediaService.start();
        llmGateway = new MockWebServer();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaService.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("mediaprocessing.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        // caption doesn't touch the OCR engine; keep the stub so no native tesseract is needed.
        r.add("mediaprocessing.ocr-engine", () -> "stub");
    }

    @Autowired WebTestClient web;
    @Autowired ObjectMapper json;

    @Test
    void passthroughFetchesImageAndAsksVisionChannel() throws Exception {
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody(new okio.Buffer().write("img-bytes".getBytes())));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-vision", "{\"merchant\":\"Lenta\"}", "stop", new LlmUsage(10, 5, 15)))));

        CaptionResult result = web.post().uri("/internal/caption")
                .bodyValue(new CaptionInput("media-7", "Return JSON with the merchant."))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CaptionResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("{\"merchant\":\"Lenta\"}");
        assertThat(result.model()).isEqualTo("mock-vision");

        RecordedRequest mediaReq = mediaService.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/media-7");

        RecordedRequest llmReq = llmGateway.takeRequest();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("\"channel\":\"vision\"")
                .contains("Return JSON with the merchant.")
                .contains("\"images\"");
    }
}
