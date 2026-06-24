package dev.fedorov.ailife.mcp.reddit.tools;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.reddit.engine.SocialTrendsSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The shared Reddit trend toolbox. {@code reddit_trends} (RD-a) returns hot / most-relevant posts for
 * a subreddit and/or search query from the configured {@link SocialTrendsSource} (the Reddit API by
 * default), each as a uniform {@link TrendHit}. The capability returns links + snippets only — no
 * LLM, no decision; folding the hits into a content brief is the calling agent's skill. Any agent
 * binds this server over MCP/SSE; the deterministic path goes through the {@code /internal/reddit-trends}
 * HTTP passthrough. Read-only.
 */
@Component
public class RedditMcpTools {

    private final SocialTrendsSource source;

    public RedditMcpTools(SocialTrendsSource source) {
        this.source = source;
    }

    @Tool(description = """
            Find hot / most-relevant Reddit posts for a subreddit and/or a search query. Pass a
            subreddit name (hot posts in it), a query (search), or both (search within the subreddit),
            and optionally maxResults (defaults to a small page). Returns a list of hits, each with the
            post title, the permalink URL, a self-text snippet, and the subreddit + score + comment
            count in metrics. The list is empty when there are no matches or no app credentials are
            configured. This is read-only trend DATA for content ideation.
            """)
    public List<TrendHit> redditTrends(String subreddit, String query, Integer maxResults) {
        return source.trends(subreddit, query, maxResults).block();
    }
}
