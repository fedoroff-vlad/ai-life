package dev.fedorov.ailife.contracts.imagegen;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Request for the {@code mcp-image-gen} capability: generate an image from a {@code prompt} and
 * (optionally) reference photos. {@code householdId} (+ optional {@code ownerId}) scope the stored
 * result in media-service. {@code refMediaIds} are media-service ids of input photos — empty for
 * plain text-to-image (board illustrations / "looks"); for a future virtual try-on it carries the
 * person photo + garment. The engine behind the capability is swappable by config (stub now, a local
 * GPU model later) with no caller change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageGenInput(
        UUID householdId,
        UUID ownerId,
        String prompt,
        List<UUID> refMediaIds) {
}
