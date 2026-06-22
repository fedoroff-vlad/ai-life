package dev.fedorov.ailife.mcp.imagegen.web;

import dev.fedorov.ailife.contracts.imagegen.ImageGenInput;
import dev.fedorov.ailife.contracts.imagegen.ImageGenResult;
import dev.fedorov.ailife.mcp.imagegen.tools.ImageGenMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code generate_image}. A capability-MCP is bound over MCP/SSE, but
 * that transport can't be MockWebServer'd, so an agent that already knows it wants an image
 * (deterministic — it has the prompt) hits this HTTP path instead. Delegates straight to the
 * {@code generate_image} tool. Mirrors mcp-market-data's {@code InternalQuoteController}.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs on
 * {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/generate")
public class InternalGenerateController {

    private final ImageGenMcpTools tools;

    public InternalGenerateController(ImageGenMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<ImageGenResult> generate(@RequestBody ImageGenInput input) {
        return Mono.fromCallable(() -> tools.generateImage(input))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
