package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

// wire-contract-exempt: domain-MCP tool output, proven by McpInventoryIntegrationTest; the agent
// wiring (and its E2E) lands in IN-c/IN-f per plans/inventory.md.

/**
 * One stored thing — mirrors an {@code inventory.item} row. The photo is the record:
 * {@code mediaId} is the media-service object id (bytes are never re-stored). {@code title} and
 * {@code description} come from the vision caption in inventory-agent and are the search corpus;
 * {@code tags} is a free-form JSON array of labels.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemDto(
        UUID id,
        UUID containerId,
        String mediaId,
        String title,
        String description,
        JsonNode tags,
        Integer qty,
        Instant createdAt) {
}
