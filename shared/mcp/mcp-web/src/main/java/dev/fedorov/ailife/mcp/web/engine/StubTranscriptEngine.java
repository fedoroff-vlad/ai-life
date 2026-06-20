package dev.fedorov.ailife.mcp.web.engine;

import dev.fedorov.ailife.contracts.web.VideoTranscript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Native-free transcript engine: returns a deterministic marker so the tool/passthrough wiring is
 * provable without yt-dlp or a network. Selected only by {@code mcp-web.transcript-engine=stub}
 * (the wiring test, degraded boxes). Mirrors {@code mcp-media-processing}'s {@code StubOcrEngine}.
 */
@Component
@ConditionalOnProperty(name = "mcp-web.transcript-engine", havingValue = "stub")
public class StubTranscriptEngine implements VideoTranscriptEngine {

    private static final Logger log = LoggerFactory.getLogger(StubTranscriptEngine.class);

    @Override
    public VideoTranscript transcribe(String url, String lang) {
        log.warn("StubTranscriptEngine: no yt-dlp wired — returning a marker for {}", url);
        return new VideoTranscript(url, null, "[stub-transcript] " + url, "stub", false);
    }
}
