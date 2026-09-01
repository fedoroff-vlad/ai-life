package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Native-free frame extractor: no real ffmpeg, returns {@code n} deterministic placeholder frames so
 * the fetch → extract → upload → {@link dev.fedorov.ailife.contracts.media.FramesResult} wiring is
 * provable without a native dependency (the upload target is a MockWebServer in the test). Selected only
 * when {@code mediaprocessing.frame-extractor=stub} (the hermetic wiring test and degraded/dev
 * environments without ffmpeg); the deployed default is {@link FfmpegFrameExtractor} — mirror of how
 * {@code StubSttEngine} was demoted by {@code WhisperSttEngine} in MP-d2b.
 */
@Component
@ConditionalOnProperty(name = "mediaprocessing.frame-extractor", havingValue = "stub")
public class StubFrameExtractor implements FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(StubFrameExtractor.class);

    @Override
    public List<byte[]> extract(byte[] bytes, String mimeType, int n) {
        int len = bytes == null ? 0 : bytes.length;
        log.warn("StubFrameExtractor: no real ffmpeg wired — returning {} marker frames for {} bytes ({})",
                n, len, mimeType);
        if (len == 0 || n <= 0) {
            return List.of();
        }
        List<byte[]> frames = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            frames.add(("[stub-frame] " + i + " of " + len + " bytes").getBytes(StandardCharsets.UTF_8));
        }
        return frames;
    }
}
