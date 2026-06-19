package dev.fedorov.ailife.mcp.mediaprocessing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-d1: the {@code caption} tool fetches bytes from media-service and asks llm-gateway's
 * {@code vision} channel about the image with the caller's instruction. Centralises the
 * vision call so no agent re-embeds it. Both upstreams are MockWebServers (no native dep).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaProcessingCaptionTest {

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

    @Autowired MediaProcessingMcpTools tools;
    @Autowired ObjectMapper json;

    @Test
    void captionFetchesImageAndAsksVisionChannel() throws Exception {
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody(new okio.Buffer().write("img-bytes".getBytes())));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-vision", "{\"merchant\":\"Lenta\"}", "stop", new LlmUsage(10, 5, 15)))));

        CaptionResult result = tools.caption("media-9", "Return JSON with the merchant.");

        assertThat(result.text()).isEqualTo("{\"merchant\":\"Lenta\"}");
        assertThat(result.model()).isEqualTo("mock-vision");

        RecordedRequest mediaReq = mediaService.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/media-9");

        RecordedRequest llmReq = llmGateway.takeRequest();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("\"channel\":\"vision\"")           // routed to the vision channel
                .contains("Return JSON with the merchant.")    // caller instruction
                .contains("\"images\"");                       // image part attached
    }
}
