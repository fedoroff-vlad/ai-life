package dev.fedorov.ailife.mcp.nutrition.web;

import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import dev.fedorov.ailife.mcp.nutrition.tools.NutritionMcpTools;
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
 * Non-MCP REST passthrough for the diet profile. An agent that has already computed a concrete
 * {@link SetDietProfileInput} (the diet-profiler flow, which extracted it from typed goals /
 * restrictions) upserts it deterministically over HTTP rather than through an LLM-driven MCP tool
 * call (the MCP/SSE binding stays for future selection but isn't MockWebServer-testable). It
 * delegates straight to {@link NutritionMcpTools#setDietProfile} / {@code getDietProfile} so the
 * tool's (household,owner) upsert keying applies identically. Mirrors mcp-wardrobe's
 * {@code InternalProfileController}. Used by nutritionist-agent (NU-d) and the analysis/ration
 * gathers (NU-e/g).
 */
@RestController
@RequestMapping("/internal/diet-profile")
public class InternalDietProfileController {

    private final NutritionMcpTools tools;

    public InternalDietProfileController(NutritionMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody SetDietProfileInput input) {
        try {
            return ResponseEntity.ok(tools.setDietProfile(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Read the person's diet profile (null ownerId = household-default). 404 when none is set yet —
     * the analysis/ration gathers treat that as "no profile" and proceed without it.
     */
    @GetMapping
    public ResponseEntity<DietProfileDto> get(@RequestParam UUID householdId,
                                              @RequestParam(required = false) UUID ownerId) {
        DietProfileDto dto = tools.getDietProfile(householdId, ownerId);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }
}
