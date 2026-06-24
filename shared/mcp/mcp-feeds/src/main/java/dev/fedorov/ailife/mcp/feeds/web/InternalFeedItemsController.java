package dev.fedorov.ailife.mcp.feeds.web;

import dev.fedorov.ailife.contracts.trends.FeedItemsInput;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.feeds.tools.FeedsMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Non-MCP REST passthrough for {@code feed_items}. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so a caller that already knows it wants feed items
 * (deterministic — it has the URL/channel) hits this HTTP path instead. Delegates straight to the
 * {@code feed_items} tool. Mirrors {@code mcp-youtube}'s {@code InternalYoutubeTrendsController}. The
 * MCP {@code @Tool} stays the entry point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/feed-items")
public class InternalFeedItemsController {

    private final FeedsMcpTools tools;

    public InternalFeedItemsController(FeedsMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<List<TrendHit>> items(@RequestBody FeedItemsInput input) {
        return Mono.fromCallable(() -> tools.feedItems(input.source(), input.maxResults()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
