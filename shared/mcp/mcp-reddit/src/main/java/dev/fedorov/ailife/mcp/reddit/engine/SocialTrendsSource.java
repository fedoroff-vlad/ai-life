package dev.fedorov.ailife.mcp.reddit.engine;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pluggable social-trends backend. The default is {@link RedditApiSource} (the Reddit API); a sibling
 * source can replace it later via {@code reddit.source} with no caller change. Mirrors
 * {@code mcp-youtube}'s {@code VideoTrendsSource}. Read-only — returns a uniform {@link TrendHit} list
 * the Coordinator folds into its trend corpus. An empty list means "no data" (e.g. no app creds, or
 * no matches), not an error; a genuine transport failure propagates on the {@link Mono}.
 */
public interface SocialTrendsSource {

    Mono<List<TrendHit>> trends(String subreddit, String query, Integer maxResults);
}
