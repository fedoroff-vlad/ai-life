package dev.fedorov.ailife.mcp.travel.web;

import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.mcp.travel.tools.TravelMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for the travel preferences. An agent that has already computed a concrete
 * {@link SetTravelProfileInput} (the travel-profiler flow, which extracted it from stated preferences
 * + geocoded the home-base city) upserts it deterministically over HTTP rather than through an
 * LLM-driven MCP tool call (the MCP/SSE binding stays for future selection but isn't
 * MockWebServer-testable). It delegates straight to {@link TravelMcpTools} so the tool's
 * (household, owner) upsert keying applies identically. Mirrors mcp-briefing's
 * {@code InternalBriefingProfileController}. Used by travel-agent (TR-c).
 */
@RestController
@RequestMapping("/internal/travel-profile")
public class InternalTravelProfileController {

    private final TravelMcpTools tools;

    public InternalTravelProfileController(TravelMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody SetTravelProfileInput input) {
        try {
            return ResponseEntity.ok(tools.setTravelProfile(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Read the person's travel prefs (null ownerId = household-default). 204 No Content when none are
     * set yet — the trip-planner treats that as "fall back to the household default / empty-profile
     * default" and proceeds.
     */
    @GetMapping
    public ResponseEntity<TravelProfileDto> get(@RequestParam UUID householdId,
                                                @RequestParam(required = false) UUID ownerId) {
        TravelProfileDto dto = tools.getTravelProfile(householdId, ownerId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }
}
