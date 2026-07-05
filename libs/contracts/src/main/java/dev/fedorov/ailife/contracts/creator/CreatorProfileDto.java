package dev.fedorov.ailife.contracts.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A person's creator track — one per (household, owner). Mirrors a {@code creator.creator_profile}
 * row; a null {@code ownerId} is the household-default. The per-user content profile the trend →
 * ideas → drafts flow is generated for. {@code platforms} (target platforms) and {@code guardrails}
 * (no-clickbait / brand rules) are free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreatorProfileDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String niche,
        String audience,
        String tone,
        JsonNode platforms,
        String goals,
        JsonNode guardrails,
        String notes,
        Instant updatedAt) {
}
