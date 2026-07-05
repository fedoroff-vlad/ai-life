package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * A structured agent-to-agent action request, routed <b>through the orchestrator</b>
 * (agents never call each other directly — locked decision, architecture.md §Decisions).
 * The requesting agent POSTs this to the orchestrator's {@code /v1/agents/invoke}, which
 * forwards it to {@code targetAgent}'s {@code POST /agents/<name>/actions/<action>} and
 * returns the {@link AgentActionResult}.
 *
 * <p>This is the synchronous inter-agent path (Stage 4 / Track C1). {@code args} is the
 * action-specific payload (e.g. event fields for {@code create_event}); {@code requestingAgent}
 * is provenance only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentActionRequest(
        String targetAgent,
        String action,
        UUID householdId,
        UUID userId,
        String requestingAgent,
        JsonNode args) {
}
