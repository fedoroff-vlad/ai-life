package dev.fedorov.ailife.contracts.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// wire-contract-exempt: domain-MCP tool output composed of the DTOs above, proven by
// McpInventoryIntegrationTest; the agent wiring (and its E2E) lands in IN-c/IN-f.

/**
 * What a scan returns: the container, the zone it stands in, and everything inside it. This is the
 * shape the card renderer (IN-d) and the deep-link/photo scan path (IN-f) consume, so one read
 * answers both "что внутри" and "где стоит" — no second round-trip for the zone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContainerViewDto(
        ContainerDto container,
        StorageZoneDto zone,
        List<ItemDto> items) {
}
