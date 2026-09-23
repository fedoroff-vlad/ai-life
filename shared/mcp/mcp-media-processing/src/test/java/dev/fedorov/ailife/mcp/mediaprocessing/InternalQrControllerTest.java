package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.contracts.media.QrInput;
import dev.fedorov.ailife.contracts.media.QrResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-f: the {@code POST /internal/qr} passthrough drives the same fetch → decode logic as the MCP
 * {@code decode_qr} tool, over the MockWebServer-testable transport inventory-agent calls
 * deterministically (IN-f). No stub engine is needed — the decoder is pure Java, so this asserts the
 * real decode against a real generated code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalQrControllerTest {

    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mediaService = new MockWebServer();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("mediaprocessing.media-service-url", () -> "http://localhost:" + mediaService.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughFetchesImageAndDecodesTheLabel() throws Exception {
        String payload = "https://t.me/ai_life_bot?start=box_9988776655443322";
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/png")
                .setBody(new okio.Buffer().write(QrDecoderTest.qrPng(payload, 240))));

        QrResult result = web.post().uri("/internal/qr")
                .bodyValue(new QrInput("media-77"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(QrResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.payload()).isEqualTo(payload);
        assertThat(result.format()).isEqualTo("QR_CODE");

        RecordedRequest mediaReq = mediaService.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/media-77");
    }

    @Test
    void codelessPhotoIsAnEmptyPayloadNotAnError() throws Exception {
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody(new okio.Buffer().write("no code here".getBytes())));

        QrResult result = web.post().uri("/internal/qr")
                .bodyValue(new QrInput("media-78"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(QrResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.payload()).isEmpty();
    }
}
