package dev.fedorov.ailife.mcp.travel.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RT-a: GPX parsing — track/waypoint extraction, elevation, route name, and XXE hardening. */
class GpxRouteParserTest {

    private final GpxRouteParser parser = new GpxRouteParser();

    /** Scenario: import a GPX track round-trips — name, track points (with elevation) and a waypoint. */
    @Test
    void parsesTrackWaypointAndName() {
        String gpx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="test">
                  <metadata><name>Утренняя пробежка</name></metadata>
                  <wpt lat="55.7500" lon="37.6200"><name>Старт</name></wpt>
                  <trk><name>Ignored track name</name><trkseg>
                    <trkpt lat="55.7500" lon="37.6200"><ele>150.0</ele></trkpt>
                    <trkpt lat="55.7510" lon="37.6210"><ele>152.5</ele></trkpt>
                  </trkseg></trk>
                </gpx>
                """;

        ParsedRoute parsed = parser.parse(gpx);

        // The first non-point <name> (metadata) wins over the later <trk> name.
        assertThat(parsed.name()).isEqualTo("Утренняя пробежка");

        RouteGeometry geometry = parsed.geometry();
        assertThat(geometry.track()).hasSize(2);
        assertThat(geometry.track().get(0).lat()).isEqualTo(55.7500);
        assertThat(geometry.track().get(0).lon()).isEqualTo(37.6200);
        assertThat(geometry.track().get(0).elevationM()).isEqualTo(150.0);
        assertThat(geometry.track().get(1).elevationM()).isEqualTo(152.5);

        assertThat(geometry.waypoints()).hasSize(1);
        RoutePoint wpt = geometry.waypoints().get(0);
        assertThat(wpt.name()).isEqualTo("Старт");
        assertThat(wpt.lat()).isEqualTo(55.7500);
        assertThat(wpt.lon()).isEqualTo(37.6200);

        assertThat(geometry.pointCount()).isEqualTo(3);
    }

    /** Scenario: XXE-hardened parse — a DTD/external-entity doc is rejected, never resolved. */
    @Test
    void rejectsDoctypeAndExternalEntities() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <gpx><wpt lat="1" lon="2"><name>&xxe;</name></wpt></gpx>
                """;

        assertThatThrownBy(() -> parser.parse(malicious))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A GPX with no points parses to an empty geometry (the tool rejects empties, not the parser). */
    @Test
    void emptyGpxYieldsEmptyGeometry() {
        String gpx = "<gpx version=\"1.1\"></gpx>";
        assertThat(parser.parse(gpx).geometry().isEmpty()).isTrue();
    }
}
