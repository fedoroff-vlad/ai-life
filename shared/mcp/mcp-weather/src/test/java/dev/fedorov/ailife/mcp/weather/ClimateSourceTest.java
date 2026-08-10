package dev.fedorov.ailife.mcp.weather;

import dev.fedorov.ailife.contracts.weather.ClimateInput;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.MonthlyNormal;
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
 * TR-a: the {@code POST /internal/climate} passthrough drives the same Open-Meteo Archive read →
 * per-month aggregation logic as the {@code climate} tool, over a MockWebServer-testable transport. A
 * MockWebServer stands in for the archive host; no external network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ClimateSourceTest {

    static MockWebServer archive;

    @BeforeAll
    static void start() throws Exception {
        archive = new MockWebServer();
        archive.start();
    }

    @AfterAll
    static void stop() throws Exception {
        archive.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("weather.climate-url", () -> "http://localhost:" + archive.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void monthlyNormalsAggregatedIntoTwelveEntries() throws Exception {
        // Two reference years, two months each (Jan/Jul) — a coastal point. The source aggregates
        // daily means → per-month normals: avg temp = mean of daily means; precip = window total / years.
        archive.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "daily": {
                            "time": ["2023-01-15", "2023-07-15", "2024-01-15", "2024-07-15"],
                            "temperature_2m_mean": [-5.0, 20.0, -3.0, 22.0],
                            "precipitation_sum": [3.0, 10.0, 5.0, 8.0]
                          }
                        }
                        """));

        ClimateNormals normals = web.post().uri("/internal/climate")
                .bodyValue(new ClimateInput(43.5855, 39.7231, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClimateNormals.class)
                .returnResult().getResponseBody();

        assertThat(normals).isNotNull();
        assertThat(normals.months()).hasSize(12);

        MonthlyNormal jan = normals.months().get(0);
        assertThat(jan.month()).isEqualTo(1);
        assertThat(jan.avgTempC()).isEqualTo(-4.0);   // mean(-5, -3)
        assertThat(jan.precipMm()).isEqualTo(4.0);    // (3 + 5) / 2 years

        MonthlyNormal jul = normals.months().get(6);
        assertThat(jul.month()).isEqualTo(7);
        assertThat(jul.avgTempC()).isEqualTo(21.0);   // mean(20, 22)
        assertThat(jul.precipMm()).isEqualTo(9.0);    // (10 + 8) / 2 years

        // A month with no data stays present but null-valued (not dropped).
        MonthlyNormal feb = normals.months().get(1);
        assertThat(feb.month()).isEqualTo(2);
        assertThat(feb.avgTempC()).isNull();
        assertThat(feb.precipMm()).isNull();

        RecordedRequest req = archive.takeRequest();
        assertThat(req.getPath())
                .startsWith("/v1/archive")
                .contains("daily=temperature_2m_mean")
                .contains("timezone=auto");
    }

    @Test
    void upstreamDownYieldsEmptyNotAnError() throws Exception {
        // Archive returns a 500 — the tool must degrade to empty normals, not surface the error.
        archive.enqueue(new MockResponse().setResponseCode(500));

        ClimateNormals normals = web.post().uri("/internal/climate")
                .bodyValue(new ClimateInput(43.5855, 39.7231, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClimateNormals.class)
                .returnResult().getResponseBody();

        assertThat(normals).isNotNull();
        assertThat(normals.months()).isEmpty();

        archive.takeRequest();
    }
}
