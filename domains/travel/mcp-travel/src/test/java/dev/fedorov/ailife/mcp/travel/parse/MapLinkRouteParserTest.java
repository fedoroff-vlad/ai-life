package dev.fedorov.ailife.mcp.travel.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RT-d1: extract coordinates from a map URL — provider order quirks, directions→track, short link→empty. */
class MapLinkRouteParserTest {

    private final MapLinkRouteParser parser = new MapLinkRouteParser();

    /** Scenario: Google @lat,lon link → one waypoint (+ the place name). */
    @Test
    void googleAtLink() {
        ParsedRoute r = parser.parse("https://www.google.com/maps/place/Кафе/@55.75,37.62,15z/data=!3m1");
        assertThat(r.geometry().waypoints()).hasSize(1);
        RoutePoint w = r.geometry().waypoints().get(0);
        assertThat(w.lat()).isEqualTo(55.75);
        assertThat(w.lon()).isEqualTo(37.62);
        assertThat(r.name()).isEqualTo("Кафе");
        assertThat(r.geometry().track()).isEmpty();
    }

    /** Scenario: Google !3d!4d data pins the exact place. */
    @Test
    void google3d4dLink() {
        ParsedRoute r = parser.parse("https://www.google.com/maps/place/X/data=!3m1!4b1!3d55.751!4d37.618");
        assertThat(r.geometry().waypoints().get(0).lat()).isEqualTo(55.751);
        assertThat(r.geometry().waypoints().get(0).lon()).isEqualTo(37.618);
    }

    /** Scenario: Yandex ll= is lon,lat → swapped into lat/lon. */
    @Test
    void yandexLlIsLonLat() {
        ParsedRoute r = parser.parse("https://yandex.ru/maps/?ll=37.62,55.75&z=13");
        RoutePoint w = r.geometry().waypoints().get(0);
        assertThat(w.lat()).isEqualTo(55.75);
        assertThat(w.lon()).isEqualTo(37.62);
    }

    /** Scenario: OSM #map fragment and geo: URI → lat,lon. */
    @Test
    void osmAndGeo() {
        ParsedRoute osm = parser.parse("https://www.openstreetmap.org/#map=13/55.75/37.62");
        assertThat(osm.geometry().waypoints().get(0).lat()).isEqualTo(55.75);
        assertThat(osm.geometry().waypoints().get(0).lon()).isEqualTo(37.62);

        ParsedRoute osmMarker = parser.parse("https://www.openstreetmap.org/?mlat=55.75&mlon=37.62#map=13/55.75/37.62");
        assertThat(osmMarker.geometry().waypoints().get(0).lat()).isEqualTo(55.75);

        ParsedRoute geo = parser.parse("geo:55.75,37.62?z=16");
        assertThat(geo.geometry().waypoints().get(0).lat()).isEqualTo(55.75);
        assertThat(geo.geometry().waypoints().get(0).lon()).isEqualTo(37.62);
    }

    /** Scenario: Google directions → an ordered track (center @ and data are excluded). */
    @Test
    void googleDirectionsBecomeTrack() {
        ParsedRoute r = parser.parse(
                "https://www.google.com/maps/dir/55.75,37.62/55.80,37.70/@55.77,37.66,13z/data=!x");
        assertThat(r.geometry().track()).hasSize(2);
        assertThat(r.geometry().track().get(0).lat()).isEqualTo(55.75);
        assertThat(r.geometry().track().get(1).lat()).isEqualTo(55.80);
        assertThat(r.geometry().waypoints()).isEmpty();
    }

    /** Scenario: an unparseable short link → empty geometry (the store rejects it upstream). */
    @Test
    void shortLinkYieldsEmpty() {
        assertThat(parser.parse("https://maps.app.goo.gl/abc123").geometry().isEmpty()).isTrue();
        assertThat(parser.parse("https://yandex.ru/maps/-/CDxyz").geometry().isEmpty()).isTrue();
    }

    /** Out-of-range coordinates are not accepted (guards against grabbing a zoom/bbox number). */
    @Test
    void rejectsOutOfRange() {
        assertThat(parser.parse("https://maps.example/?q=999.0,500.0").geometry().isEmpty()).isTrue();
    }

    @Test
    void blankIsRejected() {
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
