package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The per-subject coaching "vector" — one per (household, subject). Method weighting over
 * CBT/ACT/MI/SFBT/IFS ({@code methodWeights}), tone calibration, focus areas and hard
 * boundaries/off-limits topics are free-form JSON so the vector can grow without DDL churn.
 * Shapes that person's session prompt; editable, seeded from their own sessions (never assumed).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachProfileDto(
        UUID id,
        UUID householdId,
        UUID subject,
        JsonNode methodWeights,
        String tone,
        JsonNode focusAreas,
        JsonNode boundaries,
        boolean active,
        Instant updatedAt) {
}
