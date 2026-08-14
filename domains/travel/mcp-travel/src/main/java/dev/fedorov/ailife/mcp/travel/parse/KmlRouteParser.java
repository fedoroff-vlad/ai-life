package dev.fedorov.ailife.mcp.travel.parse;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * KML (Keyhole Markup Language) route parser, JDK StAX only — no external dependency. Reads
 * {@code <Placemark>} geometries: {@code <Point>} coordinates become named waypoints (name from the
 * placemark's {@code <name>}), {@code <LineString>}/{@code <LinearRing>} coordinates become track points.
 * KML {@code <coordinates>} are whitespace-separated {@code lon,lat[,alt]} tuples (lon first, like GeoJSON).
 * The route name is the first non-placemark {@code <name>} (Document/Folder). <b>XXE-hardened</b> like
 * {@link GpxRouteParser}. Shared by {@link KmzRouteParser} (which unzips to a KML and delegates here).
 */
@Component
public class KmlRouteParser implements RouteParser {

    @Override
    public String format() {
        return "kml";
    }

    @Override
    public ParsedRoute parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Empty KML content");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<RoutePoint> track = new ArrayList<>();
        List<RoutePoint> waypoints = new ArrayList<>();
        String routeName = null;

        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(content));
            boolean inPlacemark = false;
            String placemarkName = null;
            String geomKind = null; // "point" | "line" while inside a geometry, else null
            List<RoutePoint> placemarkPoints = new ArrayList<>(); // Point coords (named at Placemark end)
            List<RoutePoint> placemarkLine = new ArrayList<>();    // LineString/LinearRing coords (track)
            StringBuilder text = new StringBuilder();
            boolean capturing = false;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "Placemark" -> {
                                inPlacemark = true;
                                placemarkName = null;
                                placemarkPoints.clear();
                                placemarkLine.clear();
                                geomKind = null;
                            }
                            case "Point" -> geomKind = "point";
                            case "LineString", "LinearRing" -> geomKind = "line";
                            case "name", "coordinates" -> {
                                capturing = true;
                                text.setLength(0);
                            }
                            default -> { }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (capturing) {
                            text.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "name" -> {
                                String value = text.toString().trim();
                                if (inPlacemark) {
                                    placemarkName = value.isBlank() ? null : value;
                                } else if (routeName == null && !value.isBlank()) {
                                    routeName = value;
                                }
                                capturing = false;
                            }
                            case "coordinates" -> {
                                List<RoutePoint> parsed = parseCoordinates(text.toString());
                                if ("point".equals(geomKind)) {
                                    placemarkPoints.addAll(parsed);
                                } else if ("line".equals(geomKind)) {
                                    placemarkLine.addAll(parsed);
                                }
                                capturing = false;
                            }
                            case "Point", "LineString", "LinearRing" -> geomKind = null;
                            case "Placemark" -> {
                                for (RoutePoint p : placemarkPoints) {
                                    waypoints.add(RoutePoint.waypoint(p.lat(), p.lon(), p.elevationM(), placemarkName));
                                }
                                track.addAll(placemarkLine);
                                inPlacemark = false;
                                placemarkName = null;
                                placemarkPoints.clear();
                                placemarkLine.clear();
                                geomKind = null;
                            }
                            default -> { }
                        }
                    }
                    default -> { }
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid KML: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // best-effort close
                }
            }
        }
        return new ParsedRoute(routeName, new RouteGeometry(track, waypoints));
    }

    /** KML coordinates: whitespace-separated {@code lon,lat[,alt]} tuples → RoutePoints (lat/lon swapped). */
    private static List<RoutePoint> parseCoordinates(String raw) {
        List<RoutePoint> points = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return points;
        }
        for (String tuple : raw.trim().split("\\s+")) {
            String[] parts = tuple.split(",");
            if (parts.length < 2) {
                continue;
            }
            Double lon = parseDouble(parts[0]);
            Double lat = parseDouble(parts[1]);
            if (lon == null || lat == null) {
                continue;
            }
            Double ele = parts.length >= 3 ? parseDouble(parts[2]) : null;
            points.add(new RoutePoint(lat, lon, ele, null));
        }
        return points;
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
