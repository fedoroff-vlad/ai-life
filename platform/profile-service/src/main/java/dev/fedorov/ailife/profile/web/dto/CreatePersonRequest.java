package dev.fedorov.ailife.profile.web.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePersonRequest(
        @NotNull UUID householdId,
        @NotBlank @Size(max = 128) String displayName,
        @Size(max = 64) String relationship,
        @Size(max = 16) String locale,
        JsonNode interests,
        String notes,
        JsonNode leadDaysOverride) {
}
