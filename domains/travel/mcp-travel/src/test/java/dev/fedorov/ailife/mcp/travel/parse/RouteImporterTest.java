package dev.fedorov.ailife.mcp.travel.parse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** RT-a: format dispatch (case-insensitive, unsupported → error) and the haversine track distance. */
class RouteImporterTest {

    private final RouteImporter importer = new RouteImporter(List.of(
            new GpxRouteParser(), new GeoJsonRouteParser(new ObjectMapper())));

    /** Scenario: unsupported format — a format with no parser is rejected. */
    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> importer.parse("kml", "<kml/>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported route format");
    }

    /** Format matching is case-insensitive. */
    @Test
    void dispatchesCaseInsensitively() {
        ParsedRoute parsed = importer.parse("GeoJSON", "{\"type\":\"LineString\",\"coordinates\":[[1,2],[3,4]]}");
        assertThat(parsed.geometry().track()).hasSize(2);
    }

    @Test
    void normalizeFormatRejectsBlank() {
        assertThatThrownBy(() -> RouteImporter.normalizeFormat("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** One degree of longitude at the equator is ~111.2 km; distance is null for a single point. */
    @Test
    void haversineTrackDistance() {
        RouteGeometry oneDegree = new RouteGeometry(
                List.of(RoutePoint.track(0, 0, null), RoutePoint.track(0, 1, null)), List.of());
        assertThat(RouteImporter.trackDistanceMeters(oneDegree)).isCloseTo(111_195.0, within(50.0));

        RouteGeometry single = new RouteGeometry(List.of(RoutePoint.track(10, 10, null)), List.of());
        assertThat(RouteImporter.trackDistanceMeters(single)).isNull();
    }
}
