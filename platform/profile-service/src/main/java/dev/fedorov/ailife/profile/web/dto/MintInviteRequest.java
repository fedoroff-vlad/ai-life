package dev.fedorov.ailife.profile.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Owner-minted invite (ADR-0001): add the redeemer into {@code familyHouseholdId} tagged
 * {@code relationship}. {@code grantSharedAccess} defaults to true when omitted.
 */
public record MintInviteRequest(
        @NotNull UUID familyHouseholdId,
        @NotNull UUID inviterUserId,
        @Size(max = 64) String relationship,
        Boolean grantSharedAccess) {
}
