package dev.fedorov.ailife.contracts.profile;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A pre-authorized invite into a shared (family) household (ADR-0001). Minted by the owner with a
 * target household, relationship, and share-access flag; carried out-of-band as a deep-link
 * {@code start} token and redeemed by the invitee on first contact, which inserts the membership.
 * {@code status} is {@code pending | accepted | revoked}; {@code inviteeUserId}/{@code acceptedAt}
 * are set on redeem.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HouseholdInviteDto(
        UUID id,
        String token,
        UUID familyHouseholdId,
        UUID inviterUserId,
        String relationship,
        boolean grantSharedAccess,
        String status,
        UUID inviteeUserId,
        Instant createdAt,
        Instant acceptedAt) {
}
