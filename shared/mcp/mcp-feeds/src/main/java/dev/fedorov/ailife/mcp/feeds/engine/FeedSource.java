package dev.fedorov.ailife.mcp.feeds.engine;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pluggable feed backend. The default is {@link RomeJsoupFeedSource} (Rome for RSS/Atom, jsoup for
 * public Telegram channels); a sibling source can replace it later via {@code feeds.source} with no
 * caller change. Mirrors {@code mcp-youtube}'s {@code VideoTrendsSource}. Read-only — returns a
 * uniform {@link TrendHit} list the Coordinator folds into its trend corpus. {@code source} is either
 * an RSS/Atom feed URL or a public Telegram channel handle. An empty list means "no data" (e.g. empty
 * feed / unknown channel), not an error; a genuine transport/parse failure propagates on the
 * {@link Mono}.
 */
public interface FeedSource {

    Mono<List<TrendHit>> items(String source, Integer maxResults);
}
