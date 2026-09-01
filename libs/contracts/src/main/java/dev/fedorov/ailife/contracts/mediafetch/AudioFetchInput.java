package dev.fedorov.ailife.contracts.mediafetch;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Request body for the {@code mcp-media-fetch} {@code POST /internal/fetch-audio} passthrough (mirrors
 * the {@code fetch_audio} tool args): the video/media {@code url} to extract audio from, plus the
 * caller's {@code householdId} (required) and optional {@code ownerId} — the extracted audio is stored
 * as a media-service object owned by that scope, so understanding (STT) proceeds by id. The
 * deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AudioFetchInput(
        String url,
        UUID householdId,
        UUID ownerId) {
}
