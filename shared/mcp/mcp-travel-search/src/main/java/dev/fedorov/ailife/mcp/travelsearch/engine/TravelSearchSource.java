package dev.fedorov.ailife.mcp.travelsearch.engine;

import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceQueryInput;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import reactor.core.publisher.Mono;

/**
 * Pluggable live-search backend. The default is {@link TravelpayoutsTravelSearchSource} (Aviasales
 * flights + Hotellook hotels, owner-key-gated); a sibling source ({@code mcp-browser} for JS-only
 * providers) can replace it later via {@code travelsearch.source} with no caller change — the standing
 * capability-MCP doctrine (mirrors {@code mcp-market-data}'s {@code MarketDataSource} and {@code mcp-web}'s
 * {@code SearchEngine}). <b>Read-only</b> — there is deliberately no book/pay method; the agent proposes
 * options + provider deep links only (ADR-0003). When no key is wired each method returns an
 * {@code unconfigured} result (not an error); a genuine upstream failure soft-fails to an empty
 * (configured) result on the {@link Mono}, never a 500.
 */
public interface TravelSearchSource {

    Mono<PlaceResult> resolvePlace(PlaceQueryInput input);

    Mono<FlightSearchResult> searchFlights(FlightSearchInput input);

    Mono<HotelSearchResult> searchHotels(HotelSearchInput input);
}
