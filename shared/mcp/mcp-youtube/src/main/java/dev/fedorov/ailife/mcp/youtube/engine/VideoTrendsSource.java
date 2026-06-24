package dev.fedorov.ailife.mcp.youtube.engine;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pluggable video-trends backend. The default is {@link YoutubeDataApiSource} (YouTube Data API v3);
 * a sibling source can replace it later via {@code youtube.source} with no caller change. Mirrors
 * {@code mcp-food-data}'s {@code FoodDataSource}. Read-only — returns a uniform {@link TrendHit} list
 * the Coordinator folds into its trend corpus. An empty list means "no data" (e.g. no API key, or no
 * matches), not an error; a genuine transport failure propagates on the {@link Mono}.
 */
public interface VideoTrendsSource {

    Mono<List<TrendHit>> trends(String query, Integer maxResults);
}
