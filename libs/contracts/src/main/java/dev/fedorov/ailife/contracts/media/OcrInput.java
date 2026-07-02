package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-media-processing} {@code POST /internal/ocr} passthrough:
 * run OCR on a stored image. {@code mediaId} is a media-service object id (the
 * {@code storageUri} an attachment carries). Mirrors the MCP {@code ocr} tool's arg — the
 * passthrough is the deterministic, MockWebServer-testable path an agent uses when it already
 * knows it wants OCR text (MCP/SSE can't be MockWebServer'd). Used by docs-agent's
 * {@code doc-archiver} flow (D-c).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrInput(
        String mediaId) {
}
