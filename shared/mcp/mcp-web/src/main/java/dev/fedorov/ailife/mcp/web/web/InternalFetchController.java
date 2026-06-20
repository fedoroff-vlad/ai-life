package dev.fedorov.ailife.mcp.web.web;

import dev.fedorov.ailife.contracts.web.FetchUrlInput;
import dev.fedorov.ailife.contracts.web.PageContent;
import dev.fedorov.ailife.mcp.web.tools.WebMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code fetch_url}. The deterministic, MockWebServer-testable path
 * an agent calls (MCP/SSE can't be MockWebServer'd). Delegates straight to the {@code fetch_url}
 * tool; the blocking jsoup fetch runs on {@link Schedulers#boundedElastic()} so the WebFlux event
 * loop stays free. Mirrors {@link InternalSearchController}.
 */
@RestController
@RequestMapping("/internal/fetch")
public class InternalFetchController {

    private final WebMcpTools tools;

    public InternalFetchController(WebMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<PageContent> fetch(@RequestBody FetchUrlInput input) {
        return Mono.fromCallable(() -> tools.fetch_url(input.url()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
