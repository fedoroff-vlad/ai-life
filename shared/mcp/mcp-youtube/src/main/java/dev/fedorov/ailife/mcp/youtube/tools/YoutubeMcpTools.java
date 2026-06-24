package dev.fedorov.ailife.mcp.youtube.tools;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.youtube.engine.VideoTrendsSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The shared YouTube trend toolbox. {@code youtube_trends} (YT-a) returns trending / most-relevant
 * videos for a query or topic from the configured {@link VideoTrendsSource} (YouTube Data API v3 by
 * default), each as a uniform {@link TrendHit}. The capability returns links + snippets only — no
 * LLM, no decision; folding the hits into a content brief is the calling agent's skill. Any agent
 * binds this server over MCP/SSE; the deterministic path goes through the {@code /internal/youtube-trends}
 * HTTP passthrough. Read-only.
 */
@Component
public class YoutubeMcpTools {

    private final VideoTrendsSource source;

    public YoutubeMcpTools(VideoTrendsSource source) {
        this.source = source;
    }

    @Tool(description = """
            Find trending / most-relevant YouTube videos for a query or topic (a niche, a keyword).
            Pass the search query and optionally maxResults (defaults to a small page). Returns a list
            of hits, each with the video title, the watch URL, a description snippet, and the channel +
            publish date in metrics. The list is empty when there are no matches or no API key is
            configured. This is read-only trend DATA for content ideation.
            """)
    public List<TrendHit> youtubeTrends(String query, Integer maxResults) {
        return source.trends(query, maxResults).block();
    }
}
