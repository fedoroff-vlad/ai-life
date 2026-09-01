package dev.fedorov.ailife.mcp.mediafetch.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Native-free audio engine: returns a deterministic tiny byte payload so the {@code fetch_audio} tool
 * → media-service upload → {@code AudioFetchResult} wiring is provable without yt-dlp or a network
 * (the upload target is a MockWebServer in the test). Selected only by
 * {@code media-fetch.audio-engine=stub}. Mirrors {@link StubTranscriptEngine}.
 */
@Component
@ConditionalOnProperty(name = "media-fetch.audio-engine", havingValue = "stub")
public class StubAudioFetchEngine implements AudioFetchEngine {

    private static final Logger log = LoggerFactory.getLogger(StubAudioFetchEngine.class);

    @Override
    public ExtractedAudio fetch(String url) {
        log.warn("StubAudioFetchEngine: no yt-dlp wired — returning marker bytes for {}", url);
        byte[] bytes = ("[stub-audio] " + url).getBytes(StandardCharsets.UTF_8);
        return new ExtractedAudio(bytes, "audio/mp4", "stub.m4a", "[stub-audio]", "stub", null);
    }
}
