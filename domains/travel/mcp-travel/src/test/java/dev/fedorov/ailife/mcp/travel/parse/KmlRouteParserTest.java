package dev.fedorov.ailife.mcp.travel.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RT-b: KML parsing — Placemark Point→waypoint, LineString→track, [lon,lat,alt] tuples, XXE hardening. */
class KmlRouteParserTest {

    private final KmlRouteParser parser = new KmlRouteParser();

    /** Scenario: KML placemarks — a LineString and a named Point produce track + named waypoint. */
    @Test
    void parsesPlacemarks() {
        String kml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
                  <name>Маршрут выходного дня</name>
                  <Placemark><name>Кафе</name>
                    <Point><coordinates>37.625,55.755,0</coordinates></Point></Placemark>
                  <Placemark><name>Тропа</name>
                    <LineString><coordinates>
                      37.62,55.75,150 37.63,55.76,152
                    </coordinates></LineString></Placemark>
                </Document></kml>
                """;

        ParsedRoute parsed = parser.parse(kml);
        assertThat(parsed.name()).isEqualTo("Маршрут выходного дня");

        RouteGeometry g = parsed.geometry();
        assertThat(g.track()).hasSize(2);
        // KML coords are lon,lat,alt — assert the swap into lat/lon and the elevation.
        assertThat(g.track().get(0).lon()).isEqualTo(37.62);
        assertThat(g.track().get(0).lat()).isEqualTo(55.75);
        assertThat(g.track().get(0).elevationM()).isEqualTo(150.0);
        assertThat(g.track().get(1).elevationM()).isEqualTo(152.0);

        assertThat(g.waypoints()).hasSize(1);
        RoutePoint wpt = g.waypoints().get(0);
        assertThat(wpt.name()).isEqualTo("Кафе");
        assertThat(wpt.lon()).isEqualTo(37.625);
        assertThat(wpt.lat()).isEqualTo(55.755);
    }

    /** Scenario: XXE-hardened parse — a DTD/external-entity KML is rejected, never resolved. */
    @Test
    void rejectsDoctypeAndExternalEntities() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE kml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <kml><Placemark><name>&xxe;</name></Placemark></kml>
                """;
        assertThatThrownBy(() -> parser.parse(malicious))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A KML with no placemarks parses to an empty geometry (the tool rejects empties, not the parser). */
    @Test
    void emptyKmlYieldsEmptyGeometry() {
        String kml = "<kml xmlns=\"http://www.opengis.net/kml/2.2\"></kml>";
        assertThat(parser.parse(kml).geometry().isEmpty()).isTrue();
    }
}
