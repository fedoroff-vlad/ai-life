package dev.fedorov.ailife.mcp.travel.web;

import dev.fedorov.ailife.contracts.travel.ImportRouteInput;
import dev.fedorov.ailife.contracts.travel.RouteDto;
import dev.fedorov.ailife.mcp.travel.tools.RouteMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for route import (RT-a). The travel-agent (RT-c) drives import deterministically
 * over HTTP; each endpoint delegates to {@link RouteMcpTools} so validation and tenant scoping apply
 * identically. Mirrors {@code InternalTripController}. {@code IllegalArgumentException} → 400; an
 * absent/out-of-tenant route read → 204.
 */
@RestController
@RequestMapping("/internal/routes")
public class InternalRouteController {

    private final RouteMcpTools tools;

    public InternalRouteController(RouteMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> importRoute(@RequestBody ImportRouteInput input) {
        try {
            return ResponseEntity.ok(tools.importRoute(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{routeId}")
    public ResponseEntity<RouteDto> get(@PathVariable UUID routeId, @RequestParam UUID householdId) {
        RouteDto dto = tools.getRoute(routeId, householdId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @GetMapping
    public ResponseEntity<List<RouteDto>> list(@RequestParam UUID householdId,
                                               @RequestParam(required = false) UUID tripId) {
        return ResponseEntity.ok(tools.listRoutes(householdId, tripId));
    }
}
