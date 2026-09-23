package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

// wire-contract-exempt: domain-MCP tool input, proven by McpInventoryIntegrationTest; the agent
// wiring (and its E2E) lands in IN-c/IN-f per plans/inventory.md.

/**
 * Create or update a container. A null {@code id} creates one (minting its {@code qrToken} and, when
 * {@code code} is absent, the next per-household code); a non-null {@code id} updates that container
 * in place — its {@code qrToken} is never re-minted, so a rename, a zone move or a status change
 * leaves an already-printed label valid. {@code householdId} is required on create; {@code kind}
 * defaults to {@code box} and {@code status} to {@code open}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveContainerInput(
        UUID id,
        UUID householdId,
        UUID ownerId,
        UUID zoneId,
        String code,
        String label,
        String kind,
        String status,
        String destination,
        String note) {
}
