package dev.fedorov.ailife.contracts.chart;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Request for the {@code mcp-chart-render} capability: render {@code spec} into an image and store it
 * in media-service. {@code householdId} (+ optional {@code ownerId}) scope the stored result, exactly
 * as {@code mcp-image-gen}'s input does. The rendering engine is a server-side config concern
 * ({@code java2d} now) — the caller is engine-agnostic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartInput(
        UUID householdId,
        UUID ownerId,
        ChartSpec spec) {
}
