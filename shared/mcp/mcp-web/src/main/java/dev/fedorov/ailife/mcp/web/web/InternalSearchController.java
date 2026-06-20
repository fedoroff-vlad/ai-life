package dev.fedorov.ailife.mcp.web.web;

import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.mcp.web.tools.WebMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code web_search}. A capability-MCP is bound over MCP/SSE, but
 * that transport can't be MockWebServer'd, so a caller that already knows it wants a search
 * (deterministic — it has the query) hits this HTTP path instead. Delegates straight to the
 * {@code web_search} tool. Mirrors {@code mcp-media-processing}'s {@code InternalCaptionController}.
 * The MCP {@code @Tool} stays the entry point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it
 * runs on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/search")
public class InternalSearchController {

    private final WebMcpTools tools;

    public InternalSearchController(WebMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<WebSearchResult> search(@RequestBody WebSearchInput input) {
        return Mono.fromCallable(() -> tools.web_search(input.query(), input.limit()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
