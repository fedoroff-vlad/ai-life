package dev.fedorov.ailife.mcp.briefing.web;

import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.mcp.briefing.tools.BriefingMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for the briefing preferences. An agent that has already computed a
 * concrete {@link SetBriefingProfileInput} (the briefing-profiler flow, which extracted it from a
 * typed config message + geocoded the city) upserts it deterministically over HTTP rather than
 * through an LLM-driven MCP tool call (the MCP/SSE binding stays for future selection but isn't
 * MockWebServer-testable). It delegates straight to {@link BriefingMcpTools} so the tool's
 * (household, owner) upsert keying applies identically. Mirrors mcp-creator's
 * {@code InternalCreatorProfileController}. Used by briefing-agent (BR-c) and the scheduler (BR-f).
 */
@RestController
@RequestMapping("/internal/briefing-profile")
public class InternalBriefingProfileController {

    private final BriefingMcpTools tools;

    public InternalBriefingProfileController(BriefingMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody SetBriefingProfileInput input) {
        try {
            return ResponseEntity.ok(tools.setBriefingProfile(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Read the person's briefing prefs (null ownerId = household-default). 404 when none are set yet —
     * the digest flow treats that as "use defaults" and proceeds.
     */
    @GetMapping
    public ResponseEntity<BriefingProfileDto> get(@RequestParam UUID householdId,
                                                  @RequestParam(required = false) UUID ownerId) {
        BriefingProfileDto dto = tools.getBriefingProfile(householdId, ownerId);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    /** Every profile with the morning wake enabled — the scheduler's fan-out source (BR-f). */
    @GetMapping("/scheduled")
    public List<BriefingProfileDto> scheduled() {
        return tools.listScheduledProfiles();
    }
}
