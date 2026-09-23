package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

// wire-contract-exempt: capability-MCP tool input, proven by InternalQrControllerTest (the twin of
// OcrInput, whose passthrough it mirrors).

/**
 * Ask the {@code mcp-media-processing} capability to read a barcode out of a stored image.
 * {@code mediaId} is the media-service object id of the photo. Mirror of {@link OcrInput}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QrInput(String mediaId) {
}
