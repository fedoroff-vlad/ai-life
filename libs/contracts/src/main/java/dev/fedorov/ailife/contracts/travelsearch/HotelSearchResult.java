package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of {@code search_hotels} — the offers plus an {@code unconfigured} flag, mirroring
 * {@link FlightSearchResult}. {@code unconfigured=true} → no provider key wired, empty offers, caller
 * degrades to the planner MVP; a configured-but-down/empty upstream returns {@code unconfigured=false}
 * with an empty list (soft-fail). Read-only option DATA — the agent never books (ADR-0003).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotelSearchResult(
        boolean unconfigured,
        List<HotelOffer> offers) {

    /** No provider key wired → the caller degrades to the planner MVP. */
    public static HotelSearchResult degraded() {
        return new HotelSearchResult(true, List.of());
    }

    public static HotelSearchResult of(List<HotelOffer> offers) {
        return new HotelSearchResult(false, offers);
    }
}
