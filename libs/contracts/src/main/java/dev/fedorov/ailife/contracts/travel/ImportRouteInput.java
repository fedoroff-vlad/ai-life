package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Import a route/itinerary into {@code mcp-travel} (plans/travel.md §Route import, #436). {@code householdId},
 * {@code format} and {@code content} are required; {@code tripId} optionally attaches the route to a trip
 * (must belong to the same household); {@code name} overrides the name parsed from the file. {@code format}
 * is {@code gpx|geojson|kml|kmz|maplink}. {@code content} is the raw file text — except for {@code kmz} (a
 * zipped KML), where {@code content} is the <b>base64-encoded</b> archive bytes, and {@code maplink}, where
 * {@code content} is a map URL (Google/Yandex/OSM/geo) whose coordinates are extracted. The MCP parses
 * owner-supplied bytes/URLs only, it never fetches anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportRouteInput(
        UUID householdId,
        UUID tripId,
        String name,
        String format,
        String content) {
}
