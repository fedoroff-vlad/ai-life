package dev.fedorov.ailife.agentruntime.intent;

import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * How a domain renders one candidate for {@link PickConfirmActRunner} — the small per-item projection the
 * five confirm-act flows (delete×3 + calendar cancel/move) used to hand-roll identically.
 *
 * <ul>
 *   <li>{@link #id} — the candidate's identity, stored in the {@code pendingAction} and passed back to
 *       {@link TargetedActionFlow#act} on confirmation.</li>
 *   <li>{@link #label} — the <b>fully-formatted</b> user-facing token (including its own «…» quotes if the
 *       domain wants them). The runner never adds quoting of its own, so a bare title wraps itself
 *       («{@code «позвонить маме»}») while a rich finance description carries its amount inline
 *       («{@code «coffee» на 3.50 EUR}»). This label is what appears in the confirm question, the
 *       ambiguity list, and — stored in the {@code pendingAction} — the resume confirmation.</li>
 *   <li>{@link #describe} — add the domain fields the LLM needs to pick this candidate onto the JSON node
 *       the runner hands it (the {@code n} index is already set). Keep it to the fields that disambiguate
 *       (title/note/amount/date), never payload the model can't use.</li>
 * </ul>
 */
public interface CandidateView<T> {

    /** The candidate's id — stored in the {@code pendingAction}, passed to {@link TargetedActionFlow#act}. */
    UUID id(T item);

    /** Fully-formatted display token (incl. its own «…» if wanted); the runner adds no quoting. */
    String label(T item);

    /** Add the disambiguating domain fields onto {@code node} (its {@code n} index is already set). */
    void describe(ObjectNode node, T item);
}
