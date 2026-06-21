package dev.fedorov.ailife.contracts.wardrobe;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Partial content edit of a catalogued garment. {@code id} is required; every other field is
 * applied only when non-null (null = leave unchanged), so it can correct a misclassified colour
 * or category but cannot clear an already-set field. {@code householdId} / {@code createdAt} are
 * immutable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateItemInput(
        UUID id,
        String name,
        String category,
        String colour,
        String material,
        String pattern,
        String season,
        String formality,
        UUID imageMediaId) {
}
