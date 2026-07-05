package dev.fedorov.ailife.contracts.trends;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * One uniform trend result from any source capability-MCP (youtube | reddit | telegram | rss | web).
 * The creator-agent's Coordinator folds hits from every bound source into one corpus regardless of
 * origin — this is the researcher's {@code WebSearchHit} generalised across sources. {@code source}
 * is the origin id, {@code platform} the human platform label, {@code summary} an optional snippet,
 * and {@code metrics} the free-form per-source signal (view count, score, channel, published-at) when
 * the source gives it. A hit maps straight onto a {@code creator.trend} row ({@code SaveTrendInput}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrendHit(
        String source,
        String platform,
        String title,
        String url,
        String summary,
        JsonNode metrics) {
}
