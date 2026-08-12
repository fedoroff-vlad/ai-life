package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body for {@code POST /internal/search-flights}. {@code origin}/{@code destination} are city IATA codes
 * (resolve via {@code resolve_place}); {@code departDate} is required ({@code yyyy-MM-dd} or {@code
 * yyyy-MM}); {@code returnDate} null = one-way; {@code adults} defaults to 1 when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightSearchInput(
        String origin,
        String destination,
        String departDate,
        String returnDate,
        Integer adults) {
}
