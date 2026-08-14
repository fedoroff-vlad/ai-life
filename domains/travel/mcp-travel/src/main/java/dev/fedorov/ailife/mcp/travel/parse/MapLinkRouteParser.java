package dev.fedorov.ailife.mcp.travel.parse;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Map-link route parser (RT-d1) — extracts coordinates directly from a shared map URL, <b>zero-dependency,
 * no browser</b>. Many map links carry lat/lon in the URL: Google ({@code @lat,lon}, {@code !3dLAT!4dLON},
 * {@code q=lat,lon}, and {@code /dir/} directions → a track), Yandex ({@code ll=}/{@code pt=} in
 * <b>lon,lat</b> order), OpenStreetMap ({@code mlat}/{@code mlon} or {@code #map=z/lat/lon}), and
 * {@code geo:lat,lon}. {@code content} is the URL. <b>Not handled (returns empty → the store rejects, and
 * the agent tells the owner):</b> short links ({@code maps.app.goo.gl}, {@code yandex.ru/maps/-/…}) that only
 * resolve via a redirect, and polylines that render only in JS — those need {@code mcp-browser} (TR-f3).
 */
@Component
public class MapLinkRouteParser implements RouteParser {

    private static final Pattern GEO = Pattern.compile("(?i)^geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern GOOGLE_AT = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern GOOGLE_3D4D = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)");
    private static final Pattern OSM_HASH = Pattern.compile("#map=[\\d.]+/(-?\\d+\\.\\d+)/(-?\\d+\\.\\d+)");
    private static final Pattern COORD_PAIR = Pattern.compile("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern GOOGLE_PLACE = Pattern.compile("/maps/place/([^/@]+)");

    @Override
    public String format() {
        return "maplink";
    }

    @Override
    public ParsedRoute parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Empty map link");
        }
        String url = content.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        String name = placeName(url);

        // geo:lat,lon
        Matcher geo = GEO.matcher(url);
        if (geo.find()) {
            return point(name, dbl(geo.group(1)), dbl(geo.group(2)));
        }

        boolean yandex = lower.contains("yandex.");
        boolean google = lower.contains("google.") || lower.contains("goo.gl");
        boolean osm = lower.contains("openstreetmap.") || lower.contains("osm.org");

        // Google directions → a track from the lat,lon pairs in the /dir/ path.
        if (google && lower.contains("/dir/")) {
            ParsedRoute track = directionsTrack(url, name);
            if (track != null) {
                return track;
            }
        }

        if (yandex) {
            // Yandex ll=/pt= are lon,lat → swap.
            double[] lonLat = firstParamPair(url, "ll", "pt");
            if (lonLat != null && valid(lonLat[1], lonLat[0])) {
                return point(name, lonLat[1], lonLat[0]);
            }
        }
        if (google) {
            Matcher at = GOOGLE_AT.matcher(url);
            if (at.find() && valid(dbl(at.group(1)), dbl(at.group(2)))) {
                return point(name, dbl(at.group(1)), dbl(at.group(2)));
            }
            Matcher d = GOOGLE_3D4D.matcher(url);
            if (d.find() && valid(dbl(d.group(1)), dbl(d.group(2)))) {
                return point(name, dbl(d.group(1)), dbl(d.group(2)));
            }
        }
        if (osm) {
            double[] mlat = osmMarker(url);
            if (mlat != null && valid(mlat[0], mlat[1])) {
                return point(name, mlat[0], mlat[1]);
            }
            Matcher h = OSM_HASH.matcher(url);
            if (h.find() && valid(dbl(h.group(1)), dbl(h.group(2)))) {
                return point(name, dbl(h.group(1)), dbl(h.group(2)));
            }
        }

        // Generic lat,lon query param (q/query/center) — Google/OSM/geo convention is lat,lon.
        double[] q = firstParamPair(url, "q", "query", "center");
        if (q != null && valid(q[0], q[1])) {
            return point(name, q[0], q[1]);
        }

        // Nothing extractable (short link / JS-only) → empty geometry; the store rejects it upstream.
        return new ParsedRoute(name, new RouteGeometry(List.of(), List.of()));
    }

    private ParsedRoute directionsTrack(String url, String name) {
        int idx = url.toLowerCase(Locale.ROOT).indexOf("/dir/");
        String seg = url.substring(idx + 5);
        for (String stop : new String[]{"/@", "/data=", "?", "#"}) {
            int i = seg.indexOf(stop);
            if (i >= 0) {
                seg = seg.substring(0, i);
            }
        }
        List<RoutePoint> track = new ArrayList<>();
        Matcher m = COORD_PAIR.matcher(seg);
        while (m.find()) {
            double lat = dbl(m.group(1));
            double lon = dbl(m.group(2));
            if (valid(lat, lon)) {
                track.add(RoutePoint.track(lat, lon, null));
            }
        }
        return track.isEmpty() ? null : new ParsedRoute(name, new RouteGeometry(track, List.of()));
    }

    private static ParsedRoute point(String name, double lat, double lon) {
        return new ParsedRoute(name, new RouteGeometry(List.of(),
                List.of(RoutePoint.waypoint(lat, lon, null, name))));
    }

    /** First of {@code keys} whose value is a {@code a,b} pair → {@code [a, b]} (decoded); null if none. */
    private static double[] firstParamPair(String url, String... keys) {
        String query = queryString(url);
        for (String key : keys) {
            String value = paramValue(query, key);
            if (value == null) {
                continue;
            }
            Matcher m = COORD_PAIR.matcher(value);
            if (m.find()) {
                return new double[]{dbl(m.group(1)), dbl(m.group(2))};
            }
        }
        return null;
    }

    private static double[] osmMarker(String url) {
        String query = queryString(url);
        String mlat = paramValue(query, "mlat");
        String mlon = paramValue(query, "mlon");
        if (mlat == null || mlon == null) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(mlat), Double.parseDouble(mlon)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String queryString(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        String s = url.substring(q + 1);
        int h = s.indexOf('#');
        return h >= 0 ? s.substring(0, h) : s;
    }

    private static String paramValue(String query, String key) {
        if (query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (pair.substring(0, eq).equalsIgnoreCase(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String placeName(String url) {
        Matcher m = GOOGLE_PLACE.matcher(url);
        if (!m.find()) {
            return null;
        }
        String raw = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8).replace('+', ' ').trim();
        return raw.isBlank() ? null : raw;
    }

    private static boolean valid(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double dbl(String s) {
        return Double.parseDouble(s);
    }
}
