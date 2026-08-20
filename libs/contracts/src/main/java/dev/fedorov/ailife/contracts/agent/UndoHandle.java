package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * The reversible handle an agent attaches to a terminal mutating write so "отмени последнее" can undo it
 * (road-test #486, Track H). Rides on {@link IntentResponse#undo()} (opt-in via
 * {@link IntentResponse#withUndo}); the orchestrator persists it as the conversation's {@code last_mutation}
 * and, on an undo turn, dispatches {@code action} back to the recording agent's {@code /actions/undo} to
 * reverse the write.
 *
 * <p>{@code description} is the short, <b>user-facing</b> line of what would be undone
 * ("добавленную трату 1500 ₽") — safe to surface. {@code action} is the <b>opaque, internal</b> payload the
 * agent reverses with (ids / inverse-op); it is stored and handed back to the agent verbatim, never shown
 * to the user. An agent attaches this only on its terminal write path — reads / no-ops / deferrals / failed
 * writes attach none (nothing to undo).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UndoHandle(String description, JsonNode action) {
}
