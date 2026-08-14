package dev.fedorov.ailife.mcp.travel;

import dev.fedorov.ailife.contracts.travel.CreateTripInput;
import dev.fedorov.ailife.contracts.travel.ImportRouteInput;
import dev.fedorov.ailife.contracts.travel.RouteDto;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.mcp.travel.tools.RouteMcpTools;
import dev.fedorov.ailife.mcp.travel.tools.TripMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RT-a: the route-import store. Parses owner-supplied GPX/GeoJSON into {@code travel.route} and reads it
 * back, tenant-scoped. Shares a SpringBootTest context + DB, so each test scopes on its own household.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpRouteIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <metadata><name>Прогулка</name></metadata>
              <wpt lat="55.7500" lon="37.6200"><name>Старт</name></wpt>
              <trk><trkseg>
                <trkpt lat="55.7500" lon="37.6200"><ele>150.0</ele></trkpt>
                <trkpt lat="55.7600" lon="37.6300"><ele>152.0</ele></trkpt>
              </trkseg></trk>
            </gpx>
            """;

    private static final String GEOJSON = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Кафе"},
               "geometry":{"type":"Point","coordinates":[37.625,55.755]}},
              {"type":"Feature","properties":{},
               "geometry":{"type":"LineString","coordinates":[[37.62,55.75],[37.63,55.76]]}}
            ]}
            """;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    @Autowired RouteMcpTools routes;
    @Autowired TripMcpTools trips;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    /** Scenario: import a GPX track round-trips — name, points, count and a computed distance. */
    @Test
    void importGpxRoundTrips() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        RouteDto imported = routes.importRoute(new ImportRouteInput(h, null, null, "gpx", GPX));
        assertThat(imported.name()).isEqualTo("Прогулка");
        assertThat(imported.sourceFormat()).isEqualTo("gpx");
        assertThat(imported.pointCount()).isEqualTo(3);
        assertThat(imported.distanceM()).isNotNull();
        assertThat(imported.distanceM().doubleValue()).isGreaterThan(0.0);

        RouteDto read = routes.getRoute(imported.id(), h);
        assertThat(read).isNotNull();
        assertThat(read.geometry().get("track")).hasSize(2);
        assertThat(read.geometry().get("waypoints")).hasSize(1);
        assertThat(read.geometry().get("waypoints").get(0).get("name").asString()).isEqualTo("Старт");
        assertThat(read.geometry().get("track").get(0).get("lat").asDouble()).isEqualTo(55.75);
    }

    /** Scenario: import a GeoJSON attached to a trip; listRoutes filters by that trip within the household. */
    @Test
    void importGeoJsonAttachedToTripAndList() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TripDto trip = trips.createTrip(new CreateTripInput(h, null, "Поездка", null, null, null, null));

        RouteDto imported = routes.importRoute(new ImportRouteInput(h, trip.id(), "Мой маршрут", "geojson", GEOJSON));
        assertThat(imported.tripId()).isEqualTo(trip.id());
        assertThat(imported.name()).isEqualTo("Мой маршрут"); // supplied name overrides the file
        assertThat(imported.geometry().get("track")).hasSize(2);
        assertThat(imported.geometry().get("waypoints")).hasSize(1);

        assertThat(routes.listRoutes(h, trip.id())).extracting(RouteDto::id).containsExactly(imported.id());
        assertThat(routes.listRoutes(h, null)).hasSize(1);
        // A different trip in the same household has no routes.
        TripDto other = trips.createTrip(new CreateTripInput(h, null, "Другая", null, null, null, null));
        assertThat(routes.listRoutes(h, other.id())).isEmpty();
    }

    /** Scenario: an empty/pointless file is rejected and nothing is stored. */
    @Test
    void emptyRouteRejected() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThatThrownBy(() -> routes.importRoute(
                new ImportRouteInput(h, null, null, "gpx", "<gpx version=\"1.1\"></gpx>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no points");
        assertThat(routes.listRoutes(h, null)).isEmpty();
    }

    /** Scenario: attaching a route to a trip in another household is rejected (tenant isolation). */
    @Test
    void rejectsTripFromAnotherHousehold() {
        UUID h = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        seedHousehold(h);
        seedHousehold(other);
        TripDto otherTrip = trips.createTrip(new CreateTripInput(other, null, "Чужая", null, null, null, null));

        assertThatThrownBy(() -> routes.importRoute(
                new ImportRouteInput(h, otherTrip.id(), null, "gpx", GPX)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown trip in this household");
    }

    /** Scenario: cross-household read is blocked. */
    @Test
    void crossHouseholdReadBlocked() {
        UUID h = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        seedHousehold(h);
        seedHousehold(other);
        RouteDto imported = routes.importRoute(new ImportRouteInput(h, null, null, "gpx", GPX));

        assertThat(routes.getRoute(imported.id(), other)).isNull();
        assertThat(routes.listRoutes(other, null)).isEmpty();
        assertThat(routes.getRoute(imported.id(), h)).isNotNull();
    }

    /** The /internal passthrough imports over HTTP, reads it back, and 400s on a bad format. */
    @Test
    void internalEndpoints() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        RouteDto imported = client.post().uri("/internal/routes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ImportRouteInput(h, null, null, "geojson", GEOJSON))
                .exchange().expectStatus().isOk()
                .expectBody(RouteDto.class).returnResult().getResponseBody();
        assertThat(imported).isNotNull();

        List<RouteDto> listed = client.get()
                .uri(b -> b.path("/internal/routes").queryParam("householdId", h).build())
                .exchange().expectStatus().isOk()
                .expectBodyList(RouteDto.class).returnResult().getResponseBody();
        assertThat(listed).extracting(RouteDto::id).containsExactly(imported.id());

        // Out-of-tenant read → 204.
        client.get().uri(b -> b.path("/internal/routes/" + imported.id())
                        .queryParam("householdId", UUID.randomUUID()).build())
                .exchange().expectStatus().isNoContent();

        // Unsupported format → 400.
        client.post().uri("/internal/routes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ImportRouteInput(h, null, null, "docx", "..."))
                .exchange().expectStatus().isBadRequest();
    }

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }
}
