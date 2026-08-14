package dev.fedorov.ailife.mcp.travel.parse;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One point of a normalized route (plans/travel.md §Route import). {@code elevationM} and {@code name} are
 * optional — {@code name} is only set on waypoints (named POIs); track points leave it null. Serialized to
 * the {@code travel.route.geometry} JSONB blob, so nulls are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoutePoint(double lat, double lon, Double elevationM, String name) {

    public static RoutePoint track(double lat, double lon, Double elevationM) {
        return new RoutePoint(lat, lon, elevationM, null);
    }

    public static RoutePoint waypoint(double lat, double lon, Double elevationM, String name) {
        return new RoutePoint(lat, lon, elevationM, name);
    }
}
