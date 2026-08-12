package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of {@code search_flights} — the offers sorted cheapest-first, plus an {@code unconfigured} flag.
 * When {@code unconfigured} is {@code true} the capability has no provider key wired and {@code offers}
 * is empty, so the caller degrades to the planner MVP (no live options) rather than erroring. A configured
 * capability whose upstream is merely down or empty returns {@code unconfigured=false} with an empty list
 * (soft-fail, never a 500). Read-only option DATA — the agent never books (ADR-0003).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightSearchResult(
        boolean unconfigured,
        List<FlightOffer> offers) {

    /** No provider key wired → the caller degrades to the planner MVP. */
    public static FlightSearchResult degraded() {
        return new FlightSearchResult(true, List.of());
    }

    public static FlightSearchResult of(List<FlightOffer> offers) {
        return new FlightSearchResult(false, offers);
    }
}
