package dev.fedorov.ailife.mcp.travel.parse;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Dispatches a route import to the {@link RouteParser} for its format and derives cheap board metrics
 * (point count comes from {@link RouteGeometry}; track distance is a haversine sum here). Adding a format is
 * a new {@code RouteParser} bean — this class needs no change (the Spring-injected list rebuilds the map).
 */
@Component
public class RouteImporter {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final Map<String, RouteParser> parsers;

    public RouteImporter(List<RouteParser> parsers) {
        Map<String, RouteParser> byFormat = new HashMap<>();
        for (RouteParser parser : parsers) {
            byFormat.put(parser.format(), parser);
        }
        this.parsers = Map.copyOf(byFormat);
    }

    /** Parse {@code content} as {@code format}. Throws when the format has no registered parser. */
    public ParsedRoute parse(String format, String content) {
        String normalized = normalizeFormat(format);
        RouteParser parser = parsers.get(normalized);
        if (parser == null) {
            throw new IllegalArgumentException(
                    "Unsupported route format: " + format + " (supported: " + new TreeSet<>(parsers.keySet()) + ")");
        }
        return parser.parse(content);
    }

    public static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("Missing required field: format");
        }
        return format.trim().toLowerCase();
    }

    /** Haversine length of the track polyline in metres; null when the track has fewer than two points. */
    public static Double trackDistanceMeters(RouteGeometry geometry) {
        List<RoutePoint> track = geometry.track();
        if (track.size() < 2) {
            return null;
        }
        double total = 0.0;
        for (int i = 1; i < track.size(); i++) {
            total += haversine(track.get(i - 1), track.get(i));
        }
        return total;
    }

    private static double haversine(RoutePoint a, RoutePoint b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
