package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceQueryInput;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Calls the shared {@code mcp-travel-search} capability's {@code POST /internal/*} passthroughs (TR-f2) to
 * fetch <b>live flight/hotel options</b> for the TR-d trip plan when the owner asks for concrete
 * tickets/hotels. Read-only option DATA — each offer carries a provider {@code deepLink} the owner opens
 * to buy; the agent <b>never books or pays</b> ([ADR-0003](../../../../plans/adr/ADR-0003-travel-data-source.md)).
 *
 * <p><b>Owner-key-gated degrade:</b> the capability returns {@code unconfigured=true} + empty when no
 * Travelpayouts key is wired, so the planner degrades to the MVP plan (no live options) and tells the owner
 * live search isn't set up. This client also <b>soft-fails</b> a transport error to a benign empty
 * (configured) result so an unreachable capability drops only the live-options step — it does <b>not</b>
 * masquerade as {@code unconfigured} (that flag means "no key", a distinct, owner-actionable state). Mirrors
 * {@link WebSearchClient}/{@link ClimateClient} against the other shared capabilities.
 */
@Component
public class TravelSearchClient {

    private final WebClient http;

    public TravelSearchClient(@Qualifier("mcpTravelSearchWebClient") WebClient http) {
        this.http = http;
    }

    /** Place name → search codes. Transport hiccup → an empty (configured) result, so callers just skip. */
    public Mono<PlaceResult> resolvePlace(String query) {
        return http.post()
                .uri("/internal/resolve-place")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlaceQueryInput(query))
                .retrieve()
                .bodyToMono(PlaceResult.class)
                .onErrorResume(e -> Mono.just(new PlaceResult(query, null, null, null, false)));
    }

    /** Live flight offers (cheapest-first from the provider). Hiccup → empty (configured). */
    public Mono<FlightSearchResult> searchFlights(String origin, String destination, String departDate,
                                                  String returnDate, Integer adults) {
        return http.post()
                .uri("/internal/search-flights")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FlightSearchInput(origin, destination, departDate, returnDate, adults))
                .retrieve()
                .bodyToMono(FlightSearchResult.class)
                .onErrorResume(e -> Mono.just(new FlightSearchResult(false, List.of())));
    }

    /** Live hotel offers. Hiccup → empty (configured). */
    public Mono<HotelSearchResult> searchHotels(String location, String checkIn, String checkOut,
                                                Integer guests) {
        return http.post()
                .uri("/internal/search-hotels")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new HotelSearchInput(location, checkIn, checkOut, guests))
                .retrieve()
                .bodyToMono(HotelSearchResult.class)
                .onErrorResume(e -> Mono.just(new HotelSearchResult(false, List.of())));
    }
}
