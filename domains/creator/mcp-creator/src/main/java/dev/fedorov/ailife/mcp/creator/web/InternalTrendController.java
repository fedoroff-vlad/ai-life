package dev.fedorov.ailife.mcp.creator.web;

import dev.fedorov.ailife.contracts.creator.SaveTrendInput;
import dev.fedorov.ailife.contracts.creator.TrendDto;
import dev.fedorov.ailife.mcp.creator.tools.CreatorMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Non-MCP REST passthrough for caching gathered trends. After the content-strategist flow (CR-d) has
 * gathered a {@code TrendHit} corpus, it persists the whole batch deterministically over HTTP (the
 * MCP/SSE transport can't be MockWebServer'd) rather than through an LLM-driven tool call. Each item
 * delegates straight to {@link CreatorMcpTools#saveTrend}, so its idempotent-on-url dedup applies per
 * row — re-running a gather returns the existing rows instead of duplicating them. A single batch
 * endpoint keeps the persist to one HTTP round-trip even when the gather returns a dozen hits. Mirrors
 * mcp-nutrition's {@code InternalBasketController}.
 */
@RestController
@RequestMapping("/internal/trends")
public class InternalTrendController {

    private final CreatorMcpTools tools;

    public InternalTrendController(CreatorMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody List<SaveTrendInput> inputs) {
        try {
            List<TrendDto> saved = inputs.stream().map(tools::saveTrend).toList();
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
