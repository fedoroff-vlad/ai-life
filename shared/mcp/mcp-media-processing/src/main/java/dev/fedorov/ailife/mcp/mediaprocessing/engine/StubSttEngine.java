package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.TranscriptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Native-free STT engine: no real transcription, returns a deterministic marker so the
 * fetch → engine → tool wiring is provable without a native/model dependency. Selected
 * only when {@code mediaprocessing.stt-engine=stub} (the hermetic wiring test and
 * degraded/dev environments without the whisper sidecar); the deployed default is
 * {@link WhisperSttEngine} — mirror of how {@code StubOcrEngine} was demoted by
 * {@code TesseractOcrEngine} in MP-b.
 */
@Component
@ConditionalOnProperty(name = "mediaprocessing.stt-engine", havingValue = "stub")
public class StubSttEngine implements SttEngine {

    private static final Logger log = LoggerFactory.getLogger(StubSttEngine.class);

    @Override
    public TranscriptResult transcribe(byte[] bytes, String mimeType) {
        int len = bytes == null ? 0 : bytes.length;
        log.warn("StubSttEngine: no real STT wired yet (MP-d2b) — returning a marker for {} bytes ({})",
                len, mimeType);
        if (len == 0) {
            return new TranscriptResult("", null, null);
        }
        return new TranscriptResult("[stub-stt] " + len + " bytes", null, null);
    }
}
