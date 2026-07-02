package dev.fedorov.ailife.mcp.mediaprocessing.web;

import dev.fedorov.ailife.contracts.media.OcrInput;
import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for the {@code ocr} tool — the OCR twin of
 * {@link InternalCaptionController}. A capability-MCP is bound over MCP/SSE, but that transport
 * can't be MockWebServer'd, so a caller that already knows it wants OCR text (a deterministic
 * call — it has the media id) hits this HTTP passthrough instead. It delegates straight to
 * {@link MediaProcessingMcpTools#ocr} so the same fetch → OCR-engine logic and invariants apply
 * identically; the MCP {@code @Tool} stays the entry point for any future LLM-driven tool
 * selection. Used by docs-agent's {@code doc-archiver} flow (D-c) to turn a document photo into
 * the full text it archives + indexes.
 *
 * <p>The tool call is blocking ({@code .block()} inside the OCR engine per the MCP {@code @Tool}
 * convention), so it runs on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/ocr")
public class InternalOcrController {

    private final MediaProcessingMcpTools tools;

    public InternalOcrController(MediaProcessingMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<OcrResult> ocr(@RequestBody OcrInput input) {
        return Mono.fromCallable(() -> tools.ocr(input.mediaId()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
