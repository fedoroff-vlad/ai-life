package dev.fedorov.ailife.mcp.marketdata.web;

import dev.fedorov.ailife.contracts.market.Quote;
import dev.fedorov.ailife.contracts.market.QuoteInput;
import dev.fedorov.ailife.mcp.marketdata.tools.MarketDataMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code quote}. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so a caller that already knows it wants a quote (deterministic
 * — it has the symbol) hits this HTTP path instead. Delegates straight to the {@code quote} tool.
 * Mirrors {@code mcp-web}'s {@code InternalSearchController}. The MCP {@code @Tool} stays the entry
 * point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/quote")
public class InternalQuoteController {

    private final MarketDataMcpTools tools;

    public InternalQuoteController(MarketDataMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<Quote> quote(@RequestBody QuoteInput input) {
        return Mono.fromCallable(() -> tools.quote(input.symbol()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
