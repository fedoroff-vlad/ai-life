package dev.fedorov.ailife.mcp.mediaprocessing.web;

import dev.fedorov.ailife.contracts.media.CaptionInput;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for the {@code caption} vision tool. A capability-MCP is bound
 * over MCP/SSE, but that transport can't be MockWebServer'd, so a caller that already knows
 * it wants a caption (a deterministic call — it has the media id and the instruction) hits
 * this HTTP passthrough instead. It delegates straight to {@link MediaProcessingMcpTools#caption}
 * so the same fetch → vision-channel logic and invariants apply identically; the MCP {@code @Tool}
 * stays the entry point for any future LLM-driven tool selection. Mirrors mcp-finance's
 * {@code InternalTransactionController} (PR39) / mcp-money-pro-import's {@code InternalImportController}
 * (PR44). Used by finance-agent's {@code receipt-parser} flow (MP-c).
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/caption")
public class InternalCaptionController {

    private final MediaProcessingMcpTools tools;

    public InternalCaptionController(MediaProcessingMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<CaptionResult> caption(@RequestBody CaptionInput input) {
        return Mono.fromCallable(() -> tools.caption(input.mediaId(), input.instruction()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
