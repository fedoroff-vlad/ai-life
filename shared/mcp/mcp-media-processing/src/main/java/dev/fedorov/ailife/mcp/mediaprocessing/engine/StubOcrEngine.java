package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Native-free OCR engine: no real recognition, returns a deterministic marker so the
 * fetch → engine → tool wiring is provable without a native dependency. Selected only
 * when {@code mediaprocessing.ocr-engine=stub} (used by the hermetic wiring test and by
 * degraded/dev environments without tesseract); the deployed default is
 * {@link TesseractOcrEngine}.
 */
@Component
@ConditionalOnProperty(name = "mediaprocessing.ocr-engine", havingValue = "stub")
public class StubOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(StubOcrEngine.class);

    @Override
    public OcrResult extract(byte[] bytes, String mimeType) {
        int len = bytes == null ? 0 : bytes.length;
        log.warn("StubOcrEngine: no real OCR wired yet (MP-b) — returning a marker for {} bytes ({})",
                len, mimeType);
        if (len == 0) {
            return new OcrResult("", null, null);
        }
        return new OcrResult("[stub-ocr] " + len + " bytes", null, null);
    }
}
