package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * The result of an {@link AgentActionRequest}. {@code ok=true} carries an action-specific
 * {@code result} payload (e.g. {@code {"eventUid": "..."}}); {@code ok=false} carries a
 * human-readable {@code error} and no result. Returned by an agent's
 * {@code POST /agents/<name>/actions/<action>} and relayed verbatim by the orchestrator.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentActionResult(
        boolean ok,
        JsonNode result,
        String error) {

    public static AgentActionResult ok(JsonNode result) {
        return new AgentActionResult(true, result, null);
    }

    public static AgentActionResult error(String message) {
        return new AgentActionResult(false, null, message);
    }
}
