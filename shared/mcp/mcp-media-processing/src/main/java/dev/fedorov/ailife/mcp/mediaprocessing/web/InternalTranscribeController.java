package dev.fedorov.ailife.mcp.mediaprocessing.web;

import dev.fedorov.ailife.contracts.media.TranscribeInput;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for the {@code transcribe} tool — the STT twin of
 * {@link InternalOcrController} / {@link InternalCaptionController}. A capability-MCP is bound over
 * MCP/SSE, but that transport can't be MockWebServer'd, so a caller that already knows it wants a
 * transcript (a deterministic call — it has the media id) hits this HTTP passthrough instead. It
 * delegates straight to {@link MediaProcessingMcpTools#transcribe} so the same fetch → STT-engine
 * logic and invariants apply identically; the MCP {@code @Tool} stays the entry point for any future
 * LLM-driven tool selection. Used by gateway-telegram's voice-input path to turn a voice note into
 * text before the orchestrator routes it.
 *
 * <p>The tool call is blocking ({@code .block()} inside the STT engine per the MCP {@code @Tool}
 * convention), so it runs on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/transcribe")
public class InternalTranscribeController {

    private final MediaProcessingMcpTools tools;

    public InternalTranscribeController(MediaProcessingMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<TranscriptResult> transcribe(@RequestBody TranscribeInput input) {
        return Mono.fromCallable(() -> tools.transcribe(input.mediaId()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
