package dev.fedorov.ailife.mcp.travelsearch.tools;

import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceQueryInput;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import dev.fedorov.ailife.mcp.travelsearch.engine.TravelSearchSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The shared travel-search toolbox over Travelpayouts. {@code resolve_place} maps a place name to the
 * codes the searches need; {@code search_flights} / {@code search_hotels} return real priced options.
 * The capability returns option DATA + provider deep links only — <b>no booking, no payment</b>
 * (ADR-0003); the calling agent ranks and presents them, the owner buys on the provider's surface. Any
 * agent binds this server over MCP/SSE; the deterministic path goes through the {@code /internal/*}
 * passthroughs. Owner-key-gated: with no key wired every tool returns {@code unconfigured=true} + empty.
 */
@Component
public class TravelSearchMcpTools {

    private final TravelSearchSource source;

    public TravelSearchMcpTools(TravelSearchSource source) {
        this.source = source;
    }

    @Tool(name = "resolve_place", description = """
            Resolve a human place name (e.g. 'Antalya', 'Rome') to the codes the search tools need: an
            IATA city code for flights and a location id for hotels. Call this first, then pass the codes
            to search_flights / search_hotels. Returns unconfigured=true when no provider key is set.
            """)
    public PlaceResult resolvePlace(String query) {
        return source.resolvePlace(new PlaceQueryInput(query)).block();
    }

    @Tool(name = "search_flights", description = """
            Search real flight offers for a route and dates. origin/destination are city IATA codes (use
            resolve_place first); departDate is yyyy-MM-dd or yyyy-MM; returnDate null = one-way; adults
            defaults to 1. Returns offers sorted cheapest-first, each with price, number of transfers,
            airline, dates, and a provider buy link. This is read-only option DATA — it never books or pays.
            Returns unconfigured=true when no provider key is set.
            """)
    public FlightSearchResult searchFlights(String origin, String destination, String departDate,
                                            String returnDate, Integer adults) {
        return source.searchFlights(
                new FlightSearchInput(origin, destination, departDate, returnDate, adults)).block();
    }

    @Tool(name = "search_hotels", description = """
            Search real hotel offers for a location and dates. location is a place name / id (use
            resolve_place first); checkIn/checkOut are yyyy-MM-dd; guests defaults to 1. Returns offers
            with name, from-price, stars and a provider buy link. This is read-only option DATA — it never
            books or pays. Returns unconfigured=true when no provider key is set.
            """)
    public HotelSearchResult searchHotels(String location, String checkIn, String checkOut,
                                          Integer guests) {
        return source.searchHotels(new HotelSearchInput(location, checkIn, checkOut, guests)).block();
    }
}
