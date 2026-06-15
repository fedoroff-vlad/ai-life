package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * An agent's reply to a user-initiated intent (or a resume turn).
 *
 * <p>{@code pendingAction} is the Stage-4 confirmation hook: when non-null the agent is awaiting the
 * user's reply, and the orchestrator locks the conversation to this agent with that opaque payload
 * (conversation-service). The next message routes straight back to the agent's {@code /resume} with
 * the payload. A null {@code pendingAction} means the turn is complete (and the orchestrator clears
 * any lock it was resuming).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentResponse(
        String agent,
        String text,
        String llmModel,
        JsonNode pendingAction) {

    /** Back-compat for the common "no pending action" reply. */
    public IntentResponse(String agent, String text, String llmModel) {
        this(agent, text, llmModel, null);
    }
}
