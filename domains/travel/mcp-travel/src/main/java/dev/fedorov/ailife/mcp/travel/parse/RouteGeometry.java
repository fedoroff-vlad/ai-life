package dev.fedorov.ailife.mcp.travel.parse;

import java.util.List;

/**
 * A normalized route: an ordered {@code track} polyline (the path) plus named {@code waypoints} (POIs).
 * Either list may be empty (a route may be waypoints-only or a bare track). This is the format-independent
 * shape every {@link RouteParser} produces and the JSON stored in {@code travel.route.geometry}.
 */
public record RouteGeometry(List<RoutePoint> track, List<RoutePoint> waypoints) {

    public RouteGeometry {
        track = track == null ? List.of() : List.copyOf(track);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    public boolean isEmpty() {
        return track.isEmpty() && waypoints.isEmpty();
    }

    public int pointCount() {
        return track.size() + waypoints.size();
    }
}
