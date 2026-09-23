package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

// wire-contract-exempt: domain-MCP tool input, proven by McpInventoryIntegrationTest; the agent
// wiring (and its E2E) lands in IN-c/IN-f per plans/inventory.md.

/**
 * Put one photographed thing into a container. {@code containerId} is required; {@code mediaId} (the
 * media-service object id of the photo) is required too — an item without its photo is not a record
 * this domain keeps. {@code title}/{@code description} are what the vision caption produced and form
 * the search corpus; {@code qty} defaults to 1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveItemInput(
        UUID containerId,
        String mediaId,
        String title,
        String description,
        JsonNode tags,
        Integer qty) {
}
