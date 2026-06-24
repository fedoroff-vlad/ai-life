package dev.fedorov.ailife.contracts.trends;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-reddit} {@code POST /internal/reddit-trends} passthrough (mirrors
 * the {@code reddit_trends} tool args). Give a {@code subreddit} (hot posts in it), a {@code query}
 * (search), or both (search restricted to the subreddit); {@code maxResults} caps the hit count
 * (null = the source default). The passthrough is the deterministic, MockWebServer-testable path an
 * agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedditTrendsInput(
        String subreddit,
        String query,
        Integer maxResults) {
}
