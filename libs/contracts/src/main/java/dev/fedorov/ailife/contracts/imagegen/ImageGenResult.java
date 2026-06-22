package dev.fedorov.ailife.contracts.imagegen;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Result of the {@code mcp-image-gen} capability: the generated image is stored in media-service and
 * referenced by {@code mediaId} (the caller embeds it like any other media object). {@code model} is
 * the engine that produced it (e.g. {@code stub} now, a real model id later) — informational.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageGenResult(
        UUID mediaId,
        String model) {
}
