package dev.fedorov.ailife.mcp.youtube.web;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.contracts.trends.YoutubeTrendsInput;
import dev.fedorov.ailife.mcp.youtube.tools.YoutubeMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Non-MCP REST passthrough for {@code youtube_trends}. A capability-MCP is bound over MCP/SSE, but
 * that transport can't be MockWebServer'd, so a caller that already knows it wants a trend gather
 * (deterministic — it has the query) hits this HTTP path instead. Delegates straight to the
 * {@code youtube_trends} tool. Mirrors {@code mcp-food-data}'s {@code InternalFoodLookupController}.
 * The MCP {@code @Tool} stays the entry point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/youtube-trends")
public class InternalYoutubeTrendsController {

    private final YoutubeMcpTools tools;

    public InternalYoutubeTrendsController(YoutubeMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<List<TrendHit>> trends(@RequestBody YoutubeTrendsInput input) {
        return Mono.fromCallable(() -> tools.youtubeTrends(input.query(), input.maxResults()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
