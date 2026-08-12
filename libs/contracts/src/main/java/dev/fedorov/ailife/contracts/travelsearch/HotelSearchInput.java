package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body for {@code POST /internal/search-hotels}. {@code location} is a place name / hotelLocationId
 * (resolve via {@code resolve_place}); {@code checkIn}/{@code checkOut} are {@code yyyy-MM-dd};
 * {@code guests} defaults to 1 when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotelSearchInput(
        String location,
        String checkIn,
        String checkOut,
        Integer guests) {
}
