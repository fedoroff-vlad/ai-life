package dev.fedorov.ailife.profile.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateHouseholdRequest(
        @NotBlank @Size(max = 128) String name) {
}
