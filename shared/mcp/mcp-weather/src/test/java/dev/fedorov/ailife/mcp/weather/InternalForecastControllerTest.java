package dev.fedorov.ailife.mcp.weather;

import dev.fedorov.ailife.contracts.weather.ForecastInput;
import dev.fedorov.ailife.contracts.weather.Weather;
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
 * BR-a: the {@code POST /internal/forecast} passthrough drives the same Open-Meteo read → JSON parse
 * logic as the {@code forecast} tool, over a MockWebServer-testable transport (the MCP/SSE transport
 * can't be mocked). A MockWebServer stands in for Open-Meteo; no external network. Full MCP context
 * boots with the one registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalForecastControllerTest {

    static MockWebServer openMeteo;

    @BeforeAll
    static void start() throws Exception {
        openMeteo = new MockWebServer();
        openMeteo.start();
    }

    @AfterAll
    static void stop() throws Exception {
        openMeteo.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("weather.open-meteo-url", () -> "http://localhost:" + openMeteo.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughReadsOpenMeteoAndParsesTodaysRow() throws Exception {
        openMeteo.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "latitude": 52.52, "longitude": 13.41, "timezone": "Europe/Berlin",
                          "daily": {
                            "time": ["2026-06-29"],
                            "temperature_2m_max": [24.3],
                            "temperature_2m_min": [14.1],
                            "weather_code": [3],
                            "precipitation_probability_max": [20],
                            "wind_speed_10m_max": [15.2]
                          }
                        }
                        """));

        Weather w = web.post().uri("/internal/forecast")
                .bodyValue(new ForecastInput(52.52, 13.41))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Weather.class)
                .returnResult().getResponseBody();

        assertThat(w).isNotNull();
        assertThat(w.date()).isEqualTo("2026-06-29");
        assertThat(w.tempMaxC()).isEqualTo(24.3);
        assertThat(w.tempMinC()).isEqualTo(14.1);
        assertThat(w.weatherCode()).isEqualTo(3);
        assertThat(w.summary()).isEqualTo("Overcast");
        assertThat(w.precipitationProbabilityPct()).isEqualTo(20);
        assertThat(w.windSpeedMaxKmh()).isEqualTo(15.2);
        assertThat(w.latitude()).isEqualTo(52.52);
        assertThat(w.longitude()).isEqualTo(13.41);

        RecordedRequest req = openMeteo.takeRequest();
        assertThat(req.getPath())
                .startsWith("/v1/forecast")
                .contains("latitude=52.52")
                .contains("longitude=13.41")
                .contains("forecast_days=1");
    }

    @Test
    void missingDailyYieldsNullFieldsNotAnError() throws Exception {
        // Open-Meteo can answer without a daily block (e.g. bad coords) — that's "no data", not a failure.
        openMeteo.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        { "latitude": 0.0, "longitude": 0.0 }
                        """));

        Weather w = web.post().uri("/internal/forecast")
                .bodyValue(new ForecastInput(0.0, 0.0))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Weather.class)
                .returnResult().getResponseBody();

        assertThat(w).isNotNull();
        assertThat(w.date()).isNull();
        assertThat(w.tempMaxC()).isNull();
        assertThat(w.summary()).isNull();

        openMeteo.takeRequest();
    }
}
