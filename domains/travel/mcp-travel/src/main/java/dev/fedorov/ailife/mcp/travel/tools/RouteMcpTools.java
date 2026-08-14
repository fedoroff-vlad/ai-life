package dev.fedorov.ailife.mcp.travel.tools;

import dev.fedorov.ailife.contracts.travel.ImportRouteInput;
import dev.fedorov.ailife.contracts.travel.RouteDto;
import dev.fedorov.ailife.mcp.travel.domain.Route;
import dev.fedorov.ailife.mcp.travel.domain.RouteRepository;
import dev.fedorov.ailife.mcp.travel.domain.TripRepository;
import dev.fedorov.ailife.mcp.travel.parse.ParsedRoute;
import dev.fedorov.ailife.mcp.travel.parse.RouteGeometry;
import dev.fedorov.ailife.mcp.travel.parse.RouteImporter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Route-import store (RT-a): parses an owner-supplied route file (GPX/GeoJSON) into a normalized geometry
 * and persists it as a {@code travel.route} row, optionally attached to a trip. Parsing is delegated to
 * {@link RouteImporter}; balance/board rendering is not here (that is the travel-agent, RT-c). Tenant rule:
 * a route is scoped to its {@code householdId}, and a supplied {@code tripId} must belong to that same
 * household.
 */
@Component
public class RouteMcpTools {

    private final RouteRepository routes;
    private final TripRepository trips;
    private final RouteImporter importer;
    private final ObjectMapper mapper;

    public RouteMcpTools(RouteRepository routes, TripRepository trips, RouteImporter importer,
                         ObjectMapper mapper) {
        this.routes = routes;
        this.trips = trips;
        this.importer = importer;
        this.mapper = mapper;
    }

    @Tool(description = """
            Import a route/itinerary into the travel store. `householdId`, `format` and `content` are
            required; `format` is 'gpx', 'geojson', 'kml', 'kmz' or 'maplink'; `content` is the raw file text
            — except for 'kmz' (a zipped KML) where `content` is the base64-encoded archive bytes, and
            'maplink' where `content` is a map URL (Google/Yandex/OSM/geo) to extract coordinates from.
            `tripId` optionally
            attaches the route to a trip (it must belong to the same household). `name` overrides the name
            parsed from the file. The file is parsed into a normalized geometry (track polyline + named
            waypoints); an empty file (no points) is rejected. Returns the stored route with point count and
            computed track distance. The store only parses owner-supplied bytes — it never fetches anything.
            """)
    @Transactional
    public RouteDto importRoute(ImportRouteInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.format(), "format");
        requireField(input.content(), "content");
        if (input.tripId() != null
                && trips.findByIdAndHouseholdId(input.tripId(), input.householdId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown trip in this household: " + input.tripId());
        }

        ParsedRoute parsed = importer.parse(input.format(), input.content());
        RouteGeometry geometry = parsed.geometry();
        if (geometry.isEmpty()) {
            throw new IllegalArgumentException("Route has no points (no track and no waypoints)");
        }

        String name = firstNonBlank(input.name(), parsed.name(), "Route");
        String format = RouteImporter.normalizeFormat(input.format());
        Double distance = RouteImporter.trackDistanceMeters(geometry);
        BigDecimal distanceM = distance == null
                ? null : BigDecimal.valueOf(distance).setScale(4, RoundingMode.HALF_UP);
        JsonNode geometryJson = mapper.valueToTree(geometry);

        Route route = new Route(UUID.randomUUID(), input.householdId(), input.tripId(), name, format,
                geometry.pointCount(), distanceM, geometryJson);
        return routes.save(route).toDto();
    }

    @Tool(description = """
            Get an imported route by id, scoped to its household. Returns null if the route does not exist or
            belongs to another household.
            """)
    @Transactional(readOnly = true)
    public RouteDto getRoute(UUID routeId, UUID householdId) {
        requireField(routeId, "routeId");
        requireField(householdId, "householdId");
        return routes.findByIdAndHouseholdId(routeId, householdId).map(Route::toDto).orElse(null);
    }

    @Tool(description = """
            List imported routes for a household, newest first. If `tripId` is given, only routes attached to
            that trip are returned; otherwise all of the household's routes.
            """)
    @Transactional(readOnly = true)
    public List<RouteDto> listRoutes(UUID householdId, UUID tripId) {
        requireField(householdId, "householdId");
        List<Route> found = tripId == null
                ? routes.findByHouseholdIdOrderByImportedAtDesc(householdId)
                : routes.findByHouseholdIdAndTripIdOrderByImportedAtDesc(householdId, tripId);
        return found.stream().map(Route::toDto).toList();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null; // the last arg is always a non-blank default in callers
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
