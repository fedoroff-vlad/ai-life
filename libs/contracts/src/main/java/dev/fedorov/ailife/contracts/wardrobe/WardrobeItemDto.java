package dev.fedorov.ailife.contracts.wardrobe;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * One catalogued garment. Mirrors a {@code wardrobe.wardrobe_item} row. {@code category} is a
 * free-text class (top|bottom|outerwear|shoes|accessory|…); {@code imageMediaId} points at the
 * media-service object the garment photo was stored as.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WardrobeItemDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String name,
        String category,
        String colour,
        String material,
        String pattern,
        String season,
        String formality,
        UUID imageMediaId,
        Instant createdAt) {
}
