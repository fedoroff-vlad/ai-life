package dev.fedorov.ailife.contracts.wardrobe;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Add a garment to the wardrobe catalogue. Only {@code householdId} + {@code name} are required;
 * the descriptive fields (category/colour/material/pattern/season/formality) are filled by the
 * catalogue flow's vision extract when present. {@code imageMediaId} links the stored photo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddItemInput(
        UUID householdId,
        UUID ownerId,
        String name,
        String category,
        String colour,
        String material,
        String pattern,
        String season,
        String formality,
        UUID imageMediaId) {
}
