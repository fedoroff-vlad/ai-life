package dev.fedorov.ailife.mcp.mediaprocessing.web;

import dev.fedorov.ailife.contracts.media.FramesInput;
import dev.fedorov.ailife.contracts.media.FramesResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for the {@code frames} tool — the visual-channel twin of
 * {@link InternalTranscribeController} / {@link InternalOcrController}. A capability-MCP is bound over
 * MCP/SSE, but that transport can't be MockWebServer'd, so a caller that already knows it wants keyframes
 * (a deterministic call — it has the media id + frame count) hits this HTTP passthrough instead. It
 * delegates straight to {@link MediaProcessingMcpTools#frames} so the same fetch → extract → upload logic
 * and invariants apply identically; the MCP {@code @Tool} stays the entry point for any future LLM-driven
 * tool selection. Used by researcher-agent's video-understanding flow (V-c) for the visual tier.
 *
 * <p>The tool call is blocking (ffmpeg subprocess + media-service uploads), so it runs on
 * {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/frames")
public class InternalFramesController {

    private final MediaProcessingMcpTools tools;

    public InternalFramesController(MediaProcessingMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<FramesResult> frames(@RequestBody FramesInput input) {
        return Mono.fromCallable(() ->
                        tools.frames(input.mediaId(), input.n(), input.householdId(), input.ownerId()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
