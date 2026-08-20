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
 *
 * <p>{@code undo} is the optional "отмени последнее" hook (road-test #486 / Track H): a {@link UndoHandle}
 * an agent attaches on a terminal mutating write so the orchestrator remembers it as the conversation's
 * {@code last_mutation} and can reverse it on the next "отмени последнее" turn (dispatching the handle back
 * to the agent's {@code /actions/undo}). Agents opt in via {@link #withUndo}; a null {@code undo} means the
 * turn mutated nothing reversible. Reads / no-ops / deferrals / failed writes leave it null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentResponse(
        String agent,
        String text,
        String llmModel,
        JsonNode pendingAction,
        String trace,
        UndoHandle undo) {

    /** Back-compat for the common "no pending action" reply. */
    public IntentResponse(String agent, String text, String llmModel) {
        this(agent, text, llmModel, null, null, null);
    }

    /** Back-compat for a reply that carries a pending action but no trace/undo. */
    public IntentResponse(String agent, String text, String llmModel, JsonNode pendingAction) {
        this(agent, text, llmModel, pendingAction, null, null);
    }

    /** Back-compat for a reply that carries a pending action + trace but no undo. */
    public IntentResponse(String agent, String text, String llmModel, JsonNode pendingAction, String trace) {
        this(agent, text, llmModel, pendingAction, trace, null);
    }

    /** Attach a payload-free "what I read/wrote" trace for the why-trace answer (#485 / Track G). */
    public IntentResponse withTrace(String trace) {
        return new IntentResponse(agent, text, llmModel, pendingAction, trace, undo);
    }

    /** Attach an undo handle for a terminal mutating write so "отмени последнее" can reverse it (#486). */
    public IntentResponse withUndo(UndoHandle undo) {
        return new IntentResponse(agent, text, llmModel, pendingAction, trace, undo);
    }
}
