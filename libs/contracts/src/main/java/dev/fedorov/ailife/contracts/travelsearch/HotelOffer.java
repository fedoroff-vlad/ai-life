package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One hotel offer from the {@code mcp-travel-search} capability ({@code search_hotels}) — a real
 * from-price for a stay carried with {@code name}/{@code stars} and the {@code deepLink} the owner opens
 * on the provider's own surface to book. The agent <b>never books</b> (ADR-0003) — a proposable option +
 * a link, not a reservation. {@code price}/{@code currency} come from the provider; {@code stars} is the
 * hotel class (may be null).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotelOffer(
        String name,
        Double price,
        String currency,
        Double stars,
        String deepLink) {
}
