package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Today's forecast for one location, returned by the {@code mcp-weather} capability's
 * {@code forecast} tool. {@code tempMaxC}/{@code tempMinC} are the day's high/low in °C;
 * {@code precipitationProbabilityPct} is the max chance of precipitation (0–100);
 * {@code windSpeedMaxKmh} is the day's max wind; {@code weatherCode} is the WMO code and
 * {@code summary} its human label (e.g. "Partly cloudy"); {@code date} is the forecast day
 * ({@code "YYYY-MM-DD"}). Any field stays null when the source has no data for it (not an error) —
 * the briefing-agent's gather soft-fails per source. The capability returns numbers + a label only;
 * composing them into a briefing is the calling agent's job.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Weather(
        Double latitude,
        Double longitude,
        String date,
        Double tempMaxC,
        Double tempMinC,
        Integer precipitationProbabilityPct,
        Double windSpeedMaxKmh,
        Integer weatherCode,
        String summary) {
}
