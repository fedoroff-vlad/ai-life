package dev.fedorov.ailife.contracts.wardrobe;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert the style profile for a person. Keyed on (householdId, ownerId) — a null ownerId is the
 * household-default profile. {@code householdId} is required; every other field is applied as given
 * (this is a full set, not a partial merge — the "analyse me" flow recomputes the whole profile).
 * {@code suitableFabrics} and {@code measurements} are free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetStyleProfileInput(
        UUID householdId,
        UUID ownerId,
        String personType,
        String bodyShape,
        String colourType,
        JsonNode suitableFabrics,
        Integer heightCm,
        BigDecimal weightKg,
        JsonNode measurements,
        String notes,
        UUID imageMediaId) {
}
