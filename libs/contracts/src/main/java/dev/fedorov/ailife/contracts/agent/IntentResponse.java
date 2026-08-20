package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * An agent's reply to a user-initiated intent (or a resume turn).
 *
 * <p>{@code pendingAction} is the Stage-4 confirmation hook: when non-null the agent is awaiting the
 * user's reply, and the orchestrator locks the conversation to this agent with that opaque payload
 * (conversation-service). The next message routes straight back to the agent's {@code /resume} with
 * the payload. A null {@code pendingAction} means the turn is complete (and the orchestrator clears
 * any lock it was resuming).
 *
 * <p>{@code trace} is the optional "why did you do that" hook (road-test #485 / Track G, G2): a short,
 * <b>payload-free</b> line — in English, internal — of what the agent read/wrote this turn
 * (e.g. {@code "read: finance brief; wrote: logged an expense"}). The orchestrator persists it with the
 * last-route and folds it into a later "почему ты так сделал" answer. Agents opt in via {@link #withTrace};
 * a null {@code trace} means the explain answer falls back to the routing-only trace (G1). Never put raw
 * amounts, ids, or personal data here — it is surfaced to the user.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentResponse(
        String agent,
        String text,
        String llmModel,
        JsonNode pendingAction,
        String trace) {

    /** Back-compat for the common "no pending action" reply. */
    public IntentResponse(String agent, String text, String llmModel) {
        this(agent, text, llmModel, null, null);
    }

    /** Back-compat for a reply that carries a pending action but no trace. */
    public IntentResponse(String agent, String text, String llmModel, JsonNode pendingAction) {
        this(agent, text, llmModel, pendingAction, null);
    }

    /** Attach a payload-free "what I read/wrote" trace for the why-trace answer (#485 / Track G). */
    public IntentResponse withTrace(String trace) {
        return new IntentResponse(agent, text, llmModel, pendingAction, trace);
    }
}
