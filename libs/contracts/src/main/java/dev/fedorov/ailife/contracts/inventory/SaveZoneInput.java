package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

// wire-contract-exempt: domain-MCP tool input, proven by McpInventoryIntegrationTest; the agent
// wiring (and its E2E) lands in IN-c/IN-f per plans/inventory.md.

/**
 * Create or rename a storage zone. {@code householdId} + {@code name} are required; a null
 * {@code ownerId} is household-shared. Upserted on {@code (householdId, name)} case-insensitively —
 * the agent resolves a zone by the name the user said ("в кладовку"), so saying it twice must not
 * create a second кладовка. {@code labelColour} is the colour of the label stock this zone's
 * containers print on (the printer itself is black-only), so a label sheet can tell the user which
 * roll to load.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveZoneInput(
        UUID householdId,
        UUID ownerId,
        String name,
        String kind,
        String labelColour,
        String note) {
}
