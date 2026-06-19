package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.OcrResult;

/**
 * Pluggable OCR backend. MP-a ships a deterministic {@link StubOcrEngine}; MP-b swaps
 * in a real OSS engine (Tesseract) behind this same interface, so the tool layer and
 * its tests don't change. An engine never throws for ordinary "no text found" — it
 * returns an empty {@link OcrResult}; it may throw only on a genuine engine failure.
 */
public interface OcrEngine {

    /**
     * Extract text from image bytes.
     *
     * @param bytes    the raw image bytes (already fetched from media-service)
     * @param mimeType the declared MIME type, to help the engine pick a decoder
     * @return the recognised text (possibly empty), never {@code null}
     */
    OcrResult extract(byte[] bytes, String mimeType);
}
