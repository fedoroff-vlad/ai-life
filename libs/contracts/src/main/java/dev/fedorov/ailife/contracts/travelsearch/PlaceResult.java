package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A resolved travel place, returned by the {@code mcp-travel-search} capability's {@code resolve_place}
 * tool: a human place name (e.g. "Antalya") → the codes the search tools need — {@code iataCity} for the
 * flight search (the city IATA code, e.g. {@code AYT}) and {@code hotelLocationId} for the hotel search.
 * {@code unconfigured} is {@code true} when the capability has no provider key wired (the whole
 * capability degrades as one), so a caller degrades to the planner MVP rather than erroring; when
 * unconfigured the code fields are null. Read-only lookup DATA — resolving is the capability's job,
 * choosing a destination is the calling agent's.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceResult(
        String query,
        String name,
        String iataCity,
        String hotelLocationId,
        boolean unconfigured) {

    /** No provider key wired → the caller degrades to the planner MVP. */
    public static PlaceResult unconfigured(String query) {
        return new PlaceResult(query, null, null, null, true);
    }
}
