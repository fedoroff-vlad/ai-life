package dev.fedorov.ailife.mcp.travelsearch.web;

import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceQueryInput;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import dev.fedorov.ailife.mcp.travelsearch.engine.TravelSearchSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Non-MCP REST passthroughs for the travel-search tools. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so a caller that already knows what it wants (deterministic — it has
 * the codes/dates) hits these HTTP paths instead. They go straight to the reactive {@link
 * TravelSearchSource} (no {@code .block()} — unlike the MCP {@code @Tool} entry points these stay on the
 * event loop). Mirrors {@code mcp-market-data}'s {@code InternalQuoteController}. The MCP {@code @Tool}s
 * stay the entry point for future LLM-driven tool selection.
 */
@RestController
@RequestMapping("/internal")
public class InternalTravelSearchController {

    private final TravelSearchSource source;

    public InternalTravelSearchController(TravelSearchSource source) {
        this.source = source;
    }

    @PostMapping("/resolve-place")
    public Mono<PlaceResult> resolvePlace(@RequestBody PlaceQueryInput input) {
        return source.resolvePlace(input);
    }

    @PostMapping("/search-flights")
    public Mono<FlightSearchResult> searchFlights(@RequestBody FlightSearchInput input) {
        return source.searchFlights(input);
    }

    @PostMapping("/search-hotels")
    public Mono<HotelSearchResult> searchHotels(@RequestBody HotelSearchInput input) {
        return source.searchHotels(input);
    }
}
