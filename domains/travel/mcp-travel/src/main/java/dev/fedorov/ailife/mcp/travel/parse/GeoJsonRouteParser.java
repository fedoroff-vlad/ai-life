package dev.fedorov.ailife.mcp.travel.parse;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON route parser (RFC 7946). Reads a FeatureCollection, a single Feature, or a bare geometry object;
 * {@code LineString}/{@code MultiLineString} coordinates become track points, {@code Point}/{@code MultiPoint}
 * become named waypoints (name from the feature's {@code properties.name|title|label}). GeoJSON coordinates
 * are {@code [lon, lat, elevation?]} — note the lon/lat order is swapped into {@link RoutePoint}. Polygons and
 * other geometry types are ignored (MVP). Pure Jackson, no external calls.
 */
@Component
public class GeoJsonRouteParser implements RouteParser {

    private final ObjectMapper mapper;

    public GeoJsonRouteParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String format() {
        return "geojson";
    }

    @Override
    public ParsedRoute parse(String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid GeoJSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid GeoJSON: expected a JSON object");
        }
        String type = text(root, "type");
        if (type == null) {
            throw new IllegalArgumentException("Invalid GeoJSON: missing 'type'");
        }

        List<RoutePoint> track = new ArrayList<>();
        List<RoutePoint> waypoints = new ArrayList<>();
        String name = firstText(root.get("properties"), "name", "title", "label");

        switch (type) {
            case "FeatureCollection" -> {
                JsonNode features = root.get("features");
                if (features != null && features.isArray()) {
                    for (JsonNode feature : features) {
                        collectFeature(feature, track, waypoints);
                    }
                }
            }
            case "Feature" -> collectFeature(root, track, waypoints);
            default -> collectGeometry(root, null, track, waypoints); // bare geometry object
        }
        return new ParsedRoute(name, new RouteGeometry(track, waypoints));
    }

    private void collectFeature(JsonNode feature, List<RoutePoint> track, List<RoutePoint> waypoints) {
        if (feature == null || !feature.isObject()) {
            return;
        }
        String waypointName = firstText(feature.get("properties"), "name", "title", "label");
        collectGeometry(feature.get("geometry"), waypointName, track, waypoints);
    }

    private void collectGeometry(JsonNode geometry, String waypointName,
                                 List<RoutePoint> track, List<RoutePoint> waypoints) {
        if (geometry == null || !geometry.isObject()) {
            return;
        }
        String type = text(geometry, "type");
        if ("GeometryCollection".equals(type)) {
            JsonNode geometries = geometry.get("geometries");
            if (geometries != null && geometries.isArray()) {
                for (JsonNode g : geometries) {
                    collectGeometry(g, waypointName, track, waypoints);
                }
            }
            return;
        }
        JsonNode coords = geometry.get("coordinates");
        if (type == null || coords == null || !coords.isArray()) {
            return;
        }
        switch (type) {
            case "Point" -> addWaypoint(coords, waypointName, waypoints);
            case "MultiPoint" -> coords.forEach(c -> addWaypoint(c, waypointName, waypoints));
            case "LineString" -> addLine(coords, track);
            case "MultiLineString" -> coords.forEach(line -> addLine(line, track));
            default -> { /* Polygon / MultiPolygon / unknown → ignored (MVP) */ }
        }
    }

    private void addLine(JsonNode coords, List<RoutePoint> track) {
        if (coords == null || !coords.isArray()) {
            return;
        }
        for (JsonNode c : coords) {
            RoutePoint p = point(c);
            if (p != null) {
                track.add(RoutePoint.track(p.lat(), p.lon(), p.elevationM()));
            }
        }
    }

    private void addWaypoint(JsonNode coord, String name, List<RoutePoint> waypoints) {
        RoutePoint p = point(coord);
        if (p != null) {
            waypoints.add(RoutePoint.waypoint(p.lat(), p.lon(), p.elevationM(), name));
        }
    }

    /** A GeoJSON position {@code [lon, lat, elevation?]} → a RoutePoint (lat/lon swapped). */
    private RoutePoint point(JsonNode coord) {
        if (coord == null || !coord.isArray() || coord.size() < 2) {
            return null;
        }
        double lon = coord.get(0).asDouble();
        double lat = coord.get(1).asDouble();
        Double ele = coord.size() >= 3 && coord.get(2).isNumber() ? coord.get(2).asDouble() : null;
        return new RoutePoint(lat, lon, ele, null);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isString()) {
            return null;
        }
        String s = v.asString();
        return s.isBlank() ? null : s;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            String v = text(node, field);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
