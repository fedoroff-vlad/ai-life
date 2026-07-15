package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-media-processing} {@code POST /internal/transcribe} passthrough:
 * run speech-to-text on a stored audio/video clip. {@code mediaId} is a media-service object id
 * (the {@code storageUri} an attachment carries). Mirrors the MCP {@code transcribe} tool's arg —
 * the passthrough is the deterministic, MockWebServer-testable path a caller uses when it already
 * knows it wants a transcript (MCP/SSE can't be MockWebServer'd). Used by gateway-telegram to turn
 * an inbound voice note into text before routing. The audio path twin of {@link OcrInput}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscribeInput(
        String mediaId) {
}
