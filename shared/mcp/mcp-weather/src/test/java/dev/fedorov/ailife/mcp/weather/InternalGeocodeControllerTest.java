package dev.fedorov.ailife.mcp.weather;

import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.GeocodeInput;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-c1: the {@code POST /internal/geocode} passthrough drives the same Open-Meteo Geocoding read →
 * JSON parse logic as the {@code geocode} tool, over a MockWebServer-testable transport. A
 * MockWebServer stands in for the geocoding host; no external network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalGeocodeControllerTest {

    static MockWebServer geocoding;

    @BeforeAll
    static void start() throws Exception {
        geocoding = new MockWebServer();
        geocoding.start();
    }

    @AfterAll
    static void stop() throws Exception {
        geocoding.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("weather.geocode-url", () -> "http://localhost:" + geocoding.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughResolvesCityToCoordinatesAndTimezone() throws Exception {
        geocoding.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "results": [
                            {"id": 524901, "name": "Moscow", "latitude": 55.75222,
                             "longitude": 37.61556, "country": "Russia", "timezone": "Europe/Moscow"}
                          ]
                        }
                        """));

        GeoLocation loc = web.post().uri("/internal/geocode")
                .bodyValue(new GeocodeInput("Москва", "ru"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(GeoLocation.class)
                .returnResult().getResponseBody();

        assertThat(loc).isNotNull();
        assertThat(loc.name()).isEqualTo("Moscow");
        assertThat(loc.country()).isEqualTo("Russia");
        assertThat(loc.latitude()).isEqualTo(55.75222);
        assertThat(loc.longitude()).isEqualTo(37.61556);
        assertThat(loc.timezone()).isEqualTo("Europe/Moscow");

        RecordedRequest req = geocoding.takeRequest();
        assertThat(req.getPath())
                .startsWith("/v1/search")
                .contains("count=1")
                .contains("language=ru");
    }

    @Test
    void noMatchYieldsNullFieldsNotAnError() throws Exception {
        // Open-Meteo omits the `results` key entirely when nothing matches — that's "no match".
        geocoding.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("{ \"generationtime_ms\": 0.1 }"));

        GeoLocation loc = web.post().uri("/internal/geocode")
                .bodyValue(new GeocodeInput("zzzznowhere", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(GeoLocation.class)
                .returnResult().getResponseBody();

        assertThat(loc).isNotNull();
        assertThat(loc.name()).isNull();
        assertThat(loc.latitude()).isNull();
        assertThat(loc.timezone()).isNull();

        geocoding.takeRequest();
    }
}
