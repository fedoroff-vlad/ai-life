package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-media-processing} {@code POST /internal/caption}
 * passthrough: ask an LLM vision model about a stored image. {@code mediaId} is a
 * media-service object id (the {@code storageUri} an attachment carries);
 * {@code instruction} tells the model what to return (a free-form description, or a
 * structured extraction the caller then parses, e.g. "Return JSON with amount,
 * currency, merchant, date"). Mirrors the MCP {@code caption} tool's args — the
 * passthrough is the deterministic, MockWebServer-testable path an agent uses when it
 * already knows it wants a caption (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptionInput(
        String mediaId,
        String instruction) {
}
