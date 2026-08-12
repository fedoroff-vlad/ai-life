package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One flight offer from the {@code mcp-travel-search} capability ({@code search_flights}) — a real price
 * for a route/date carried with the metadata a plan needs to rank it ({@code transfers}, {@code airline},
 * dates) and the {@code deepLink} the owner opens on the provider's own surface to buy. The agent
 * <b>never books</b> (ADR-0003) — this is a proposable option + a link, not a reservation.
 * {@code price}/{@code currency} come from the provider; {@code transfers} is the number of stops
 * (0 = direct).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightOffer(
        Double price,
        String currency,
        Integer transfers,
        String airline,
        String departDate,
        String returnDate,
        String deepLink) {
}
