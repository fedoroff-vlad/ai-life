package dev.fedorov.ailife.mcp.mediafetch.web;

import dev.fedorov.ailife.contracts.mediafetch.AudioFetchInput;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchResult;
import dev.fedorov.ailife.mcp.mediafetch.tools.MediaFetchMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code fetch_audio}. The deterministic, MockWebServer-testable path an
 * agent calls (MCP/SSE can't be MockWebServer'd). Delegates straight to the tool; the blocking yt-dlp
 * subprocess + media-service upload run on {@link Schedulers#boundedElastic()} so the WebFlux event
 * loop stays free. Mirrors {@link InternalTranscribeController}.
 */
@RestController
@RequestMapping("/internal/fetch-audio")
public class InternalFetchAudioController {

    private final MediaFetchMcpTools tools;

    public InternalFetchAudioController(MediaFetchMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<AudioFetchResult> fetchAudio(@RequestBody AudioFetchInput input) {
        return Mono.fromCallable(() -> tools.fetch_audio(input.url(), input.householdId(), input.ownerId()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
