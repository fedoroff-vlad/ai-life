package dev.fedorov.ailife.mcp.imagegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.imagegen.ImageGenInput;
import dev.fedorov.ailife.contracts.imagegen.ImageGenResult;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code POST /internal/generate} passthrough runs the same logic as the {@code generate_image}
 * tool over a MockWebServer-testable transport (MCP/SSE can't be mocked). The default stub engine
 * produces a placeholder PNG; a MockWebServer stands in for media-service. Full MCP context boots
 * with the one registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalGenerateControllerTest {

    static MockWebServer mediaService;
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

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
        r.add("image-gen.media-service-url", () -> "http://localhost:" + mediaService.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void stubEngineRendersStoresAndReturnsMediaId() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(MAPPER.writeValueAsString(new MediaObjectDto(
                        mediaId, householdId, null, "image", "image/png", 1234L, null, "image-gen", Instant.now()))));

        ImageGenResult result = web.post().uri("/internal/generate")
                .bodyValue(new ImageGenInput(householdId, null, "navy wool coat, editorial, beige ground", List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ImageGenResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.mediaId()).isEqualTo(mediaId);
        assertThat(result.model()).isEqualTo("stub");

        // The generated PNG was uploaded to media-service as a multipart image.
        RecordedRequest up = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(up.getMethod()).isEqualTo("POST");
        assertThat(up.getPath()).isEqualTo("/v1/media");
        String body = up.getBody().readUtf8();
        assertThat(body).contains("image/png").contains(householdId.toString());
    }
}
