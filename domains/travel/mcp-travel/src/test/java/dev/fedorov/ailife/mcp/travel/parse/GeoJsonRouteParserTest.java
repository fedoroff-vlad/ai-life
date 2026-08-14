package dev.fedorov.ailife.mcp.travel.parse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RT-a: GeoJSON parsing — LineString→track, Point→waypoint, and the [lon,lat] → lat/lon swap. */
class GeoJsonRouteParserTest {

    private final GeoJsonRouteParser parser = new GeoJsonRouteParser(new ObjectMapper());

    /** Scenario: import a GeoJSON LineString + named Point — coordinates map [lon,lat] correctly. */
    @Test
    void parsesLineStringAndNamedPoint() {
        String geojson = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"name":"leg"},
                   "geometry":{"type":"LineString","coordinates":[[37.62,55.75],[37.63,55.76,10.0]]}},
                  {"type":"Feature","properties":{"name":"Кафе"},
                   "geometry":{"type":"Point","coordinates":[37.625,55.755]}}
                ]}
                """;

        RouteGeometry geometry = parser.parse(geojson).geometry();

        assertThat(geometry.track()).hasSize(2);
        // GeoJSON coords are [lon, lat] — assert they land in the right RoutePoint fields.
        assertThat(geometry.track().get(0).lon()).isEqualTo(37.62);
        assertThat(geometry.track().get(0).lat()).isEqualTo(55.75);
        assertThat(geometry.track().get(1).elevationM()).isEqualTo(10.0);

        assertThat(geometry.waypoints()).hasSize(1);
        RoutePoint wpt = geometry.waypoints().get(0);
        assertThat(wpt.name()).isEqualTo("Кафе");
        assertThat(wpt.lon()).isEqualTo(37.625);
        assertThat(wpt.lat()).isEqualTo(55.755);
    }

    /** A bare geometry object (no Feature wrapper) is accepted. */
    @Test
    void parsesBareGeometry() {
        String geojson = "{\"type\":\"LineString\",\"coordinates\":[[1,2],[3,4]]}";
        assertThat(parser.parse(geojson).geometry().track()).hasSize(2);
    }

    /** Invalid JSON is rejected with a clear error, not a null geometry. */
    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
