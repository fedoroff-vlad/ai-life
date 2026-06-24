package dev.fedorov.ailife.mcp.reddit.web;

import dev.fedorov.ailife.contracts.trends.RedditTrendsInput;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.reddit.tools.RedditMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Non-MCP REST passthrough for {@code reddit_trends}. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so a caller that already knows it wants a trend gather
 * (deterministic — it has the subreddit/query) hits this HTTP path instead. Delegates straight to the
 * {@code reddit_trends} tool. Mirrors {@code mcp-youtube}'s {@code InternalYoutubeTrendsController}.
 * The MCP {@code @Tool} stays the entry point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/reddit-trends")
public class InternalRedditTrendsController {

    private final RedditMcpTools tools;

    public InternalRedditTrendsController(RedditMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<List<TrendHit>> trends(@RequestBody RedditTrendsInput input) {
        return Mono.fromCallable(() -> tools.redditTrends(input.subreddit(), input.query(), input.maxResults()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
