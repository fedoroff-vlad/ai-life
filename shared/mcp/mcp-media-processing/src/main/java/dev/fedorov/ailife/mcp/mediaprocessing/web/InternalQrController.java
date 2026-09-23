package dev.fedorov.ailife.mcp.mediaprocessing.web;

import dev.fedorov.ailife.contracts.media.QrInput;
import dev.fedorov.ailife.contracts.media.QrResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for the {@code decode_qr} tool — the barcode twin of
 * {@link InternalOcrController}. Same rationale: a capability-MCP is bound over MCP/SSE, which can't
 * be MockWebServer'd, so a caller that already knows it wants a barcode read (it has the media id)
 * hits this HTTP path. Delegates straight to {@link MediaProcessingMcpTools#decodeQr} so the same
 * fetch → decode invariants apply. Used by inventory-agent (IN-f) when the owner photographs a
 * container's label instead of scanning it with a phone camera.
 *
 * <p>The tool call blocks (the media fetch is {@code .block()}ed per the MCP {@code @Tool}
 * convention), so it runs on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/qr")
public class InternalQrController {

    private final MediaProcessingMcpTools tools;

    public InternalQrController(MediaProcessingMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<QrResult> decodeQr(@RequestBody QrInput input) {
        return Mono.fromCallable(() -> tools.decodeQr(input.mediaId()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
