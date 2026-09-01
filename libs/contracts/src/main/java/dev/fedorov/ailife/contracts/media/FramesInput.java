package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Request body for the {@code mcp-media-processing} {@code POST /internal/frames} passthrough (mirrors
 * the {@code frames} tool args): extract {@code n} evenly-spaced keyframes from a stored video by
 * {@code mediaId} (a media-service object id). Each frame is stored back as a media-service image owned
 * by the caller's {@code householdId} (required) and optional {@code ownerId} — so understanding is the
 * existing id-based {@code caption} tool per frame. The deterministic, MockWebServer-testable path an
 * agent calls (MCP/SSE can't be MockWebServer'd). The visual-channel twin of {@link TranscribeInput}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FramesInput(
        String mediaId,
        int n,
        UUID householdId,
        UUID ownerId) {
}
