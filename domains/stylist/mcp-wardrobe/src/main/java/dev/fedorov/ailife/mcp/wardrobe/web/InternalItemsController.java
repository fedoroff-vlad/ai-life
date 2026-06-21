package dev.fedorov.ailife.mcp.wardrobe.web;

import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import dev.fedorov.ailife.mcp.wardrobe.tools.WardrobeMcpTools;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for reading the wardrobe. Delegates to the {@code list_items} tool
 * (same filters), so a system caller — stylist-agent's capsule flow gathering the household's
 * garments — reads over HTTP rather than the MCP/SSE transport (which can't be MockWebServer'd).
 * Mirrors mcp-tasks' {@code GET /internal/tasks}.
 */
@RestController
@RequestMapping("/internal/items")
public class InternalItemsController {

    private final WardrobeMcpTools tools;

    public InternalItemsController(WardrobeMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public List<WardrobeItemDto> list(@RequestParam UUID householdId,
                                      @RequestParam(required = false) String category) {
        return tools.listItems(householdId, category);
    }
}
