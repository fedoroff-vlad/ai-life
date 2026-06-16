package dev.fedorov.ailife.agentruntime.coordinate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of a {@link Coordinator#coordinate} run.
 *
 * @param text     the LLM's synthesized answer (the proposal to show / act on)
 * @param gathered the assembled context object (one field per successful gather step) —
 *                 retained so the caller can audit what fed the synthesis or build a
 *                 {@code pendingAction} from it
 * @param llmModel the model that produced {@code text} (for the response contract)
 */
public record CoordinationResult(String text, JsonNode gathered, String llmModel) {
}
