package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

// wire-contract-exempt: domain-MCP tool output, proven by McpInventoryIntegrationTest; the agent
// wiring (and its E2E) lands in IN-c/IN-f per plans/inventory.md.

/**
 * One container — mirrors an {@code inventory.container} row. A container is whatever carries a
 * label: a box, a shelf, a bin, or a zone's {@code loose} pseudo-container for open storage.
 * {@code code} is the short human id printed beside the QR ("B-07"); {@code qrToken} is the opaque
 * printed identity and <b>never</b> changes — a rename or a move must not invalidate a label already
 * stuck on a box. {@code status} tracks the move ({@code open|packed|in_transit|unpacked}),
 * {@code destination} is the target room after it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContainerDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        UUID zoneId,
        String code,
        String label,
        String kind,
        String qrToken,
        String status,
        String destination,
        String note,
        Instant createdAt,
        Instant closedAt) {
}
