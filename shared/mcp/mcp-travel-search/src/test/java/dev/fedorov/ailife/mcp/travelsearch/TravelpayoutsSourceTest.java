package dev.fedorov.ailife.mcp.travelsearch;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.mcp.travelsearch.config.McpTravelSearchProperties;
import dev.fedorov.ailife.mcp.travelsearch.engine.TravelpayoutsTravelSearchSource;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TR-f1: the Travelpayouts source maps a flights/hotels request → the upstream call → parsed offers, and
 * soft-fails an upstream error to an empty (configured) result. A MockWebServer stands in for
 * Travelpayouts (all three hosts point at it); no external network, a real token wired (the no-key path is
 * asserted separately in {@link TravelSearchControllerTest}). Structure/field mapping, not live fidelity.
 */
class TravelpayoutsSourceTest {

    private MockWebServer upstream;
    private TravelpayoutsTravelSearchSource source;

    @BeforeEach
    void start() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
        String base = "http://localhost:" + upstream.getPort();

        McpTravelSearchProperties props = new McpTravelSearchProperties();
        props.setToken("test-token");
        props.setMarker("12345");
        props.setCurrency("rub");
        props.setAviasalesApiUrl(base);
        props.setAviasalesSiteUrl("https://www.aviasales.com");
        props.setHotellookApiUrl(base);
        props.setHotellookSiteUrl("https://search.hotellook.com");
        props.setAutocompleteUrl(base);

        WebClient http = WebClient.builder().baseUrl(base).build();
        source = new TravelpayoutsTravelSearchSource(http, http, http, props, new ObjectMapper());
    }

    @AfterEach
    void stop() throws Exception {
        upstream.shutdown();
    }

    @Test
    void searchFlightsParsesSortsCheapestFirstAndCarriesMarkerDeepLink() throws Exception {
        upstream.enqueue(json("""
                {"success":true,"data":[
                  {"origin":"MOW","destination":"AYT","price":31000,"transfers":1,"airline":"SU",
                   "departure_at":"2026-09-12T10:00:00+03:00","return_at":"2026-09-19T14:00:00+03:00",
                   "link":"/search/MOW1209AYT1909?a=1"},
                  {"origin":"MOW","destination":"AYT","price":25500,"transfers":0,"airline":"TK",
                   "departure_at":"2026-09-12T08:00:00+03:00","return_at":"2026-09-19T20:00:00+03:00",
                   "link":"/search/MOW1209AYT1909direct"}
                ]}"""));

        FlightSearchResult result = source.searchFlights(
                new FlightSearchInput("MOW", "AYT", "2026-09-12", "2026-09-19", 2)).block();

        assertThat(result).isNotNull();
        assertThat(result.unconfigured()).isFalse();
        assertThat(result.offers()).hasSize(2);
        // cheapest first
        assertThat(result.offers().get(0).price()).isEqualTo(25500.0);
        assertThat(result.offers().get(0).transfers()).isZero();
        assertThat(result.offers().get(0).airline()).isEqualTo("TK");
        assertThat(result.offers().get(0).currency()).isEqualTo("RUB");
        // buy deep link = site host + relative link + affiliate marker (link already had a query → '&')
        assertThat(result.offers().get(0).deepLink())
                .startsWith("https://www.aviasales.com/search/")
                .contains("marker=12345");
        assertThat(result.offers().get(1).deepLink()).contains("&marker=12345");

        RecordedRequest req = upstream.takeRequest();
        assertThat(req.getPath())
                .startsWith("/aviasales/v3/prices_for_dates")
                .contains("origin=MOW").contains("destination=AYT")
                .contains("one_way=false").contains("token=test-token");
    }

    @Test
    void searchHotelsParsesOffersWithMarkerDeepLink() throws Exception {
        upstream.enqueue(json("""
                [
                  {"hotelName":"Rixos Premium","priceFrom":42000.0,"stars":5,"hotelId":"9911"},
                  {"hotelName":"Side Star","priceFrom":18500.0,"stars":4,"hotelId":"7722"}
                ]"""));

        HotelSearchResult result = source.searchHotels(
                new HotelSearchInput("Antalya", "2026-09-12", "2026-09-19", 2)).block();

        assertThat(result).isNotNull();
        assertThat(result.unconfigured()).isFalse();
        assertThat(result.offers()).hasSize(2);
        assertThat(result.offers().get(0).name()).isEqualTo("Rixos Premium");
        assertThat(result.offers().get(0).price()).isEqualTo(42000.0);
        assertThat(result.offers().get(0).stars()).isEqualTo(5.0);
        assertThat(result.offers().get(0).currency()).isEqualTo("RUB");
        assertThat(result.offers().get(0).deepLink())
                .startsWith("https://search.hotellook.com/hotels/9911")
                .contains("marker=12345");
    }

    @Test
    void upstreamErrorSoftFailsToEmptyConfiguredResult() throws Exception {
        upstream.enqueue(new MockResponse().setResponseCode(500));

        FlightSearchResult result = source.searchFlights(
                new FlightSearchInput("MOW", "AYT", "2026-09-12", null, 1)).block();

        assertThat(result).isNotNull();
        assertThat(result.unconfigured()).isFalse();   // configured — just no data
        assertThat(result.offers()).isEmpty();          // soft-fail, not a 500 to the caller
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
