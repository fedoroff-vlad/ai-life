package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One imported route/itinerary (plans/travel.md §Route import, #436). Mirrors a {@code travel.route} row:
 * a household-scoped route optionally attached to a {@code tripId}. {@code sourceFormat} is the format it
 * was imported from ({@code gpx|geojson|...}); {@code pointCount} is the total of track + waypoint points;
 * {@code distanceM} is the computed track length in metres (null when the track is a single point);
 * {@code geometry} is the normalized JSON blob {@code {track:[{lat,lon,elevationM?}], waypoints:[{lat,lon,
 * elevationM?,name?}]}}. The MCP only persists — no map rendering here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteDto(
        UUID id,
        UUID householdId,
        UUID tripId,
        String name,
        String sourceFormat,
        int pointCount,
        BigDecimal distanceM,
        JsonNode geometry,
        Instant importedAt) {
}
