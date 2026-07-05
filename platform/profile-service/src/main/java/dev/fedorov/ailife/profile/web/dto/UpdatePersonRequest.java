package dev.fedorov.ailife.profile.web.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

/**
 * Partial update. {@code null} field = leave unchanged. {@code displayName} is
 * the only required-non-null when present (it stays {@code NOT NULL} in DB).
 */
public record UpdatePersonRequest(
        @Size(min = 1, max = 128) String displayName,
        @Size(max = 64) String relationship,
        @Size(max = 16) String locale,
        JsonNode interests,
        String notes,
        JsonNode leadDaysOverride) {
}
