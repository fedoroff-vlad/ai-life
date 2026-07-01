package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A geocoded place, returned by the {@code mcp-weather} {@code geocode} tool. Resolves a stated city
 * name to the {@code latitude}/{@code longitude} the {@code forecast} tool needs, plus the place's
 * canonical {@code name}, {@code country}, and IANA {@code timezone}. The briefing-agent geocodes a
 * user's stated city once (at profile-set time) and stores the coordinates + timezone in the
 * briefing profile. All fields are null when the source finds no match (not an error).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeoLocation(
        String name,
        String country,
        Double latitude,
        Double longitude,
        String timezone) {
}
