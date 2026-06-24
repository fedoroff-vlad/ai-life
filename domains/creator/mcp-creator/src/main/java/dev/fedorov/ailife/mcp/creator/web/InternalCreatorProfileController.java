package dev.fedorov.ailife.mcp.creator.web;

import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import dev.fedorov.ailife.mcp.creator.tools.CreatorMcpTools;
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
 * Non-MCP REST passthrough for the creator track. An agent that has already computed a concrete
 * {@link SetCreatorProfileInput} (the creator-profiler flow, which extracted it from a typed niche /
 * audience / tone message) upserts it deterministically over HTTP rather than through an LLM-driven
 * MCP tool call (the MCP/SSE binding stays for future selection but isn't MockWebServer-testable). It
 * delegates straight to {@link CreatorMcpTools#setCreatorProfile} / {@code getCreatorProfile} so the
 * tool's (household,owner) upsert keying applies identically. Mirrors mcp-nutrition's
 * {@code InternalDietProfileController}. Used by creator-agent (CR-c) and the trend gather (CR-d).
 */
@RestController
@RequestMapping("/internal/creator-profile")
public class InternalCreatorProfileController {

    private final CreatorMcpTools tools;

    public InternalCreatorProfileController(CreatorMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody SetCreatorProfileInput input) {
        try {
            return ResponseEntity.ok(tools.setCreatorProfile(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Read the person's creator track (null ownerId = household-default). 404 when none is set yet —
     * the trend gather treats that as "no track" and proceeds without it.
     */
    @GetMapping
    public ResponseEntity<CreatorProfileDto> get(@RequestParam UUID householdId,
                                                 @RequestParam(required = false) UUID ownerId) {
        CreatorProfileDto dto = tools.getCreatorProfile(householdId, ownerId);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }
}
