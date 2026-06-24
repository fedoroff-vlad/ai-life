package dev.fedorov.ailife.contracts.trends;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-feeds} {@code POST /internal/feed-items} passthrough (mirrors the
 * {@code feed_items} tool args). {@code source} is either an <b>RSS/Atom feed URL</b> (starts with
 * {@code http}) or a <b>public Telegram channel</b> handle (e.g. {@code durov} or {@code @durov});
 * {@code maxResults} caps the hit count (null = the source default). The passthrough is the
 * deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedItemsInput(
        String source,
        Integer maxResults) {
}
