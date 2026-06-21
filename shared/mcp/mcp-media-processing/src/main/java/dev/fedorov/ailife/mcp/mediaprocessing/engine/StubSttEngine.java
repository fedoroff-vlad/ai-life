package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.TranscriptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Native-free STT engine: no real transcription, returns a deterministic marker so the
 * fetch → engine → tool wiring is provable without a native/model dependency (MP-d2a).
 * This is the default bean; MP-d2b adds a real whisper-backed engine as the deployed
 * default and demotes this stub to {@code mediaprocessing.stt-engine=stub} only — mirror
 * of how {@code StubOcrEngine} was demoted by {@code TesseractOcrEngine} in MP-b.
 */
@Component
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
