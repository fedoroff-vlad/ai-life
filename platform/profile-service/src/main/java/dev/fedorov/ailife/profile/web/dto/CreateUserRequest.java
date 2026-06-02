package dev.fedorov.ailife.profile.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateUserRequest(
        @NotNull UUID householdId,
        @NotBlank @Size(max = 128) String displayName,
        @Size(max = 16) String locale,
        Long telegramUserId,
        @Size(max = 32) String role) {
}
