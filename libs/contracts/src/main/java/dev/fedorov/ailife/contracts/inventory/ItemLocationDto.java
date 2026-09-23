package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

// wire-contract-exempt: domain-MCP tool output composed of the DTOs above, proven by
// McpInventoryIntegrationTest; the agent wiring (and its E2E) lands in IN-c/IN-f.

/**
 * A search hit with its place: the matched item plus the container and zone holding it. The question
 * this domain answers is "где лежит X", so the search returns the location, not a bare item the
 * caller would have to resolve.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemLocationDto(
        ItemDto item,
        ContainerDto container,
        StorageZoneDto zone) {
}
