package dev.fedorov.ailife.mcp.wardrobe.web;

import dev.fedorov.ailife.contracts.wardrobe.AddItemInput;
import dev.fedorov.ailife.mcp.wardrobe.tools.WardrobeMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for adding a garment. A capability is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so an agent that already has a concrete
 * {@link AddItemInput} (the catalogue flow, which parsed it from a photo caption) hits this HTTP
 * passthrough instead. It delegates straight to {@link WardrobeMcpTools#addItem} so the tool's
 * invariants (required-field checks) apply identically; the MCP {@code @Tool} stays the entry point
 * for any future LLM-driven tool selection. Mirrors mcp-finance's {@code InternalTransactionController}
 * (PR39). Used by stylist-agent's wardrobe-catalogue flow (ST-c).
 */
@RestController
@RequestMapping("/internal/item")
public class InternalItemController {

    private final WardrobeMcpTools tools;

    public InternalItemController(WardrobeMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddItemInput input) {
        try {
            return ResponseEntity.ok(tools.addItem(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
