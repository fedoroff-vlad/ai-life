package dev.fedorov.ailife.contracts.profile;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-routing view of a user's memberships (ADR-0001, slice 4). Splits the user's household set
 * into the single <b>personal</b> household (the self-membership, {@code relationship IS NULL} — the
 * private-scope target) and the <b>shared</b> family households they were approved into
 * ({@code relationship} set). Callers route a new item to {@code personalHouseholdId} for a private
 * choice and to a shared household for a shared one; when {@code sharedHouseholdIds} is empty a shared
 * choice degrades to personal. Distinct from {@code GET /v1/users/{id}/households}, which returns the
 * flat union without the personal/shared split the write-path routing needs.
 */
public record HouseholdRoutingDto(UUID personalHouseholdId, List<UUID> sharedHouseholdIds) {
}
