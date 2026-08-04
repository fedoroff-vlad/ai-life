package dev.fedorov.ailife.profile.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Link a contact (core.people) to the operator (core.users) it became — ADR-0001 item 6.
 * The {@code userId} must reference an existing user (else 422).
 */
public record LinkPersonUserRequest(@NotNull UUID userId) {
}
