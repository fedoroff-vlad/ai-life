package dev.fedorov.ailife.mcp.travelsearch;

import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceQueryInput;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TR-f1 degrade path: with <b>no provider key</b> wired (the default — token unset) the full MCP context
 * still boots, and every {@code /internal/*} passthrough returns {@code unconfigured=true} + empty
 * <b>without</b> touching the upstream (no network needed). This is what keeps CI green with no key and
 * lets travel-agent degrade to the planner MVP. The configured parsing is covered by
 * {@link TravelpayoutsSourceTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TravelSearchControllerTest {

    @Autowired WebTestClient web;

    @Test
    void flightsSearchDegradesWhenUnconfigured() {
        FlightSearchResult result = web.post().uri("/internal/search-flights")
                .bodyValue(new FlightSearchInput("MOW", "AYT", "2026-09-12", "2026-09-19", 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FlightSearchResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.unconfigured()).isTrue();
        assertThat(result.offers()).isEmpty();
    }

    @Test
    void hotelsSearchDegradesWhenUnconfigured() {
        HotelSearchResult result = web.post().uri("/internal/search-hotels")
                .bodyValue(new HotelSearchInput("Antalya", "2026-09-12", "2026-09-19", 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HotelSearchResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.unconfigured()).isTrue();
        assertThat(result.offers()).isEmpty();
    }

    @Test
    void resolvePlaceDegradesWhenUnconfigured() {
        PlaceResult result = web.post().uri("/internal/resolve-place")
                .bodyValue(new PlaceQueryInput("Antalya"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PlaceResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.unconfigured()).isTrue();
        assertThat(result.iataCity()).isNull();
    }
}
