package dev.fedorov.ailife.contracts.chart;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Result of the {@code mcp-chart-render} capability: the rendered chart is stored in media-service and
 * referenced by {@code mediaId} (the caller embeds it like any other media object — e.g. inline in a
 * Telegram report). {@code engine} is the renderer that produced it ({@code java2d} now) —
 * informational, mirrors {@code ImageGenResult.model()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartResult(
        UUID mediaId,
        String engine) {
}
