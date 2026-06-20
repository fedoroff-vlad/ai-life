package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-web} {@code POST /internal/transcribe} passthrough (mirrors the
 * {@code transcribe_video} tool args): the video URL, and an optional preferred subtitle language.
 * The deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscribeInput(
        String url,
        String lang) {
}
