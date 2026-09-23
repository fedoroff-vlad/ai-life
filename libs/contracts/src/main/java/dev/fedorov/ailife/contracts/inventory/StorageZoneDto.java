package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

// wire-contract-exempt: domain-MCP tool output, proven by McpInventoryIntegrationTest; the agent
// wiring (and its E2E) lands in IN-c/IN-f per plans/inventory.md.

/**
 * One storage zone — mirrors an {@code inventory.storage_zone} row. A zone is a physical place that
 * holds containers (кладовка / гараж / дача / балкон). Scoped {@code (householdId, ownerId)} (a null
 * {@code ownerId} is household-shared). {@code kind} is a coarse class
 * ({@code room|garage|dacha|balcony|closet|other}). {@code labelColour} is the colour of the label
 * stock this zone's containers are printed on — a direct-thermal printer prints black only, so the
 * roll's colour is how a zone stays recognisable across the room without scanning anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StorageZoneDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String name,
        String kind,
        String labelColour,
        String note,
        Instant createdAt) {
}
