package dev.fedorov.ailife.mcp.web.web;

import dev.fedorov.ailife.contracts.web.TranscribeInput;
import dev.fedorov.ailife.contracts.web.VideoTranscript;
import dev.fedorov.ailife.mcp.web.tools.WebMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code transcribe_video}. The deterministic, MockWebServer-testable
 * path an agent calls (MCP/SSE can't be MockWebServer'd). Delegates straight to the tool; the
 * blocking yt-dlp subprocess runs on {@link Schedulers#boundedElastic()} so the WebFlux event loop
 * stays free. Mirrors {@link InternalFetchController}.
 */
@RestController
@RequestMapping("/internal/transcribe")
public class InternalTranscribeController {

    private final WebMcpTools tools;

    public InternalTranscribeController(WebMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<VideoTranscript> transcribe(@RequestBody TranscribeInput input) {
        return Mono.fromCallable(() -> tools.transcribe_video(input.url(), input.lang()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
