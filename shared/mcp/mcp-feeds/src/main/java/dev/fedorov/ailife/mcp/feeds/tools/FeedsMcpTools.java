package dev.fedorov.ailife.mcp.feeds.tools;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.feeds.engine.FeedSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The shared feeds toolbox. {@code feed_items} (FE-a) returns the latest items from an RSS/Atom feed
 * or a public Telegram channel via the configured {@link FeedSource} (Rome for RSS, jsoup for
 * Telegram by default), each as a uniform {@link TrendHit}. The capability returns links + snippets
 * only — no LLM, no decision; folding the items into a content brief is the calling agent's skill.
 * Any agent binds this server over MCP/SSE; the deterministic path goes through the
 * {@code /internal/feed-items} HTTP passthrough. Read-only.
 */
@Component
public class FeedsMcpTools {

    private final FeedSource source;

    public FeedsMcpTools(FeedSource source) {
        this.source = source;
    }

    @Tool(description = """
            Fetch the latest items from a feed. Pass either an RSS/Atom feed URL (starts with http) or
            a public Telegram channel handle (e.g. "durov" or "@durov"), and optionally maxResults
            (defaults to a small page). Returns a list of items, each with the title, the item/post
            URL, a text snippet, and source metadata in metrics. The list is empty when the feed is
            empty or the channel is unknown. This is read-only trend DATA for content ideation.
            """)
    public List<TrendHit> feedItems(String source, Integer maxResults) {
        return this.source.items(source, maxResults).block();
    }
}
