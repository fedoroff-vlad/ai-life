package dev.fedorov.ailife.mcp.wardrobe.web;

import dev.fedorov.ailife.contracts.wardrobe.SetStyleProfileInput;
import dev.fedorov.ailife.mcp.wardrobe.tools.WardrobeMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for the style profile. An agent that has already computed a concrete
 * {@link SetStyleProfileInput} (the "analyse me" flow, which extracted it from a self-photo + the
 * user's typed params) upserts it deterministically over HTTP rather than through an LLM-driven MCP
 * tool call (the MCP/SSE binding stays for future selection but isn't MockWebServer-testable). It
 * delegates straight to {@link WardrobeMcpTools#setStyleProfile} so the tool's (household,owner)
 * upsert keying applies identically. Mirrors {@link InternalItemController} (ST-c1). Used by
 * stylist-agent's analyse-me flow (ST-d).
 */
@RestController
@RequestMapping("/internal/profile")
public class InternalProfileController {

    private final WardrobeMcpTools tools;

    public InternalProfileController(WardrobeMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody SetStyleProfileInput input) {
        try {
            return ResponseEntity.ok(tools.setStyleProfile(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
