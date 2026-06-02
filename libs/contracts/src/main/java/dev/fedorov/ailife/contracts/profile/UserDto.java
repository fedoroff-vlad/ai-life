package dev.fedorov.ailife.contracts.profile;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDto(
        UUID id,
        UUID householdId,
        String displayName,
        String locale,
        Long telegramUserId,
        String role,
        Instant createdAt) {
}
