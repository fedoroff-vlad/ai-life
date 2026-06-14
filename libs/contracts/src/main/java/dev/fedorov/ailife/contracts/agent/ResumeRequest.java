package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sent by the orchestrator to an agent's {@code POST /agents/<name>/resume} when the user's message
 * is a reply to that agent's open question (an active conversation route-lock). Carries the new
 * {@code message} plus the {@code pendingAction} the agent stored when it asked — so the agent can
 * resolve the confirmation without re-deriving context. The agent replies with an
 * {@link IntentResponse}; a non-null {@code pendingAction} on that reply re-locks (still awaiting),
 * null clears the lock (resolved).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeRequest(
        NormalizedMessage message,
        JsonNode pendingAction) {
}
