package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default OCR engine for MP-a: no real recognition. It proves the wiring end-to-end
 * (media fetch → engine → tool result) without a native dependency, and is replaced
 * by a real Tesseract engine in MP-b (same {@link OcrEngine} interface). The returned
 * text is a deterministic marker that includes the byte count, so a test can assert
 * the bytes flowed through; empty input yields empty text.
 */
@Component
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
