package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of the {@code mcp-web} {@code web_search} tool: the echoed query plus the ranked
 * hits (engine order, trimmed to the requested limit). {@code hits} is never null (empty when
 * nothing matched).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebSearchResult(
        String query,
        List<WebSearchHit> hits) {
}
