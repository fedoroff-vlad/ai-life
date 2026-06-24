package dev.fedorov.ailife.contracts.trends;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-youtube} {@code POST /internal/youtube-trends} passthrough (mirrors
 * the {@code youtube_trends} tool args). {@code query} is the niche / topic to find trending videos
 * for; {@code maxResults} caps the hit count (null = the source default). The passthrough is the
 * deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YoutubeTrendsInput(
        String query,
        Integer maxResults) {
}
