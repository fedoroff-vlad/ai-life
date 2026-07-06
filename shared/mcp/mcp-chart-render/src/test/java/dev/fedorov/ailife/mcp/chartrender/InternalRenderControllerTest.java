package dev.fedorov.ailife.mcp.chartrender;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.chart.ChartInput;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.contracts.chart.ChartSeries;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code POST /internal/render} passthrough runs the same logic as the {@code render_chart} tool
 * over a MockWebServer-testable transport (MCP/SSE can't be mocked). The default Java2D engine renders
 * a PNG; a MockWebServer stands in for media-service. Full MCP context boots with the one tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalRenderControllerTest {

    static MockWebServer mediaService;
    static final ObjectMapper MAPPER = new ObjectMapper();

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
        r.add("chart-render.media-service-url", () -> "http://localhost:" + mediaService.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void java2dEngineRendersStoresAndReturnsMediaId() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(MAPPER.writeValueAsString(new MediaObjectDto(
                        mediaId, householdId, null, "image", "image/png", 4321L, null, "chart-render", Instant.now()))));

        ChartSpec spec = new ChartSpec(
                "bar",
                "Spending by category",
                List.of("Food", "Transport", "Home"),
                List.of(new ChartSeries("2026", List.of(1200.0, 450.0, 800.0))),
                "₽");

        ChartResult result = web.post().uri("/internal/render")
                .bodyValue(new ChartInput(householdId, null, spec))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChartResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.mediaId()).isEqualTo(mediaId);
        assertThat(result.engine()).isEqualTo("java2d");

        // The rendered PNG was uploaded to media-service as a multipart image.
        RecordedRequest up = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(up.getMethod()).isEqualTo("POST");
        assertThat(up.getPath()).isEqualTo("/v1/media");
        String body = up.getBody().readUtf8();
        assertThat(body).contains("image/png").contains(householdId.toString());
    }
}
