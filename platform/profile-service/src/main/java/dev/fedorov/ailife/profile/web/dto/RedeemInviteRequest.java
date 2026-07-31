package dev.fedorov.ailife.profile.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Redeem a pending invite for the given (already-created) invitee user. */
public record RedeemInviteRequest(
        @NotNull UUID inviteeUserId) {
}
