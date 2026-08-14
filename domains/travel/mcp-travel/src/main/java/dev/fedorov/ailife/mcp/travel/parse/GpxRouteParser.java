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
 * GPX (GPS Exchange) route parser, JDK StAX only — no external dependency. {@code <trkpt>}/{@code <rtept>}
 * become track points, {@code <wpt>} become named waypoints; each point's {@code lat}/{@code lon} come from
 * its attributes and the optional {@code <ele>} child. The route name is the first non-point {@code <name>}
 * (metadata/trk/rte). <b>XXE-hardened:</b> DTD processing and external-entity resolution are disabled, so a
 * hostile GPX cannot read local files or reach the network — a malformed/doctype doc is rejected, never
 * resolved.
 */
@Component
public class GpxRouteParser implements RouteParser {

    @Override
    public String format() {
        return "gpx";
    }

    @Override
    public ParsedRoute parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Empty GPX content");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // XXE hardening — reject DTDs and never resolve external entities.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<RoutePoint> track = new ArrayList<>();
        List<RoutePoint> waypoints = new ArrayList<>();
        String routeName = null;

        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(content));
            Double lat = null;
            Double lon = null;
            Double ele = null;
            String pointName = null;
            String pointKind = null; // "wpt" | "track" while inside a point element, else null
            StringBuilder text = new StringBuilder();
            boolean capturing = false;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "wpt", "trkpt", "rtept" -> {
                                lat = attrDouble(reader, "lat");
                                lon = attrDouble(reader, "lon");
                                ele = null;
                                pointName = null;
                                pointKind = "wpt".equals(reader.getLocalName()) ? "wpt" : "track";
                            }
                            case "ele", "name" -> {
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
                            case "ele" -> {
                                if (pointKind != null) {
                                    ele = parseDouble(text.toString());
                                }
                                capturing = false;
                            }
                            case "name" -> {
                                String value = text.toString().trim();
                                if (pointKind != null) {
                                    pointName = value.isBlank() ? null : value;
                                } else if (routeName == null && !value.isBlank()) {
                                    routeName = value;
                                }
                                capturing = false;
                            }
                            case "wpt" -> {
                                if (lat != null && lon != null) {
                                    waypoints.add(RoutePoint.waypoint(lat, lon, ele, pointName));
                                }
                                pointKind = null;
                                lat = lon = ele = null;
                                pointName = null;
                            }
                            case "trkpt", "rtept" -> {
                                if (lat != null && lon != null) {
                                    track.add(RoutePoint.track(lat, lon, ele));
                                }
                                pointKind = null;
                                lat = lon = ele = null;
                            }
                            default -> { }
                        }
                    }
                    default -> { }
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid GPX: " + e.getMessage());
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

    private static Double attrDouble(XMLStreamReader reader, String name) {
        return parseDouble(reader.getAttributeValue(null, name));
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
