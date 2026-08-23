package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import tools.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The domain-supplied seam for a <b>pick → confirm → act</b> flow driven by {@link PickConfirmActRunner}
 * (ADR-0004). A domain implements this small adapter; the runner owns the two-turn orchestration over the
 * Stage-4 pending-action lock, the LLM round-trip + selection parse, the shared Russian wording, the
 * confirm gate, and the resume soft-fail — everything the five copy-pasted flows duplicated.
 *
 * <p>A delete flow supplies {@link #candidates}, {@link #view}, {@link #act} and its {@link #nouns};
 * a move/edit flow additionally overrides {@link #missing} (the "picked a target but a required field is
 * absent → re-ask" gate) and reads the extra LLM fields the runner threads through {@code params}.
 *
 * @param <T> the candidate domain type (a task, a transaction, a note, an event…)
 */
public interface TargetedActionFlow<T> {

    /** The {@code *-delete}/{@code *-cancel}/{@code *-move} SKILL name — the routing SSOT (SKILL.md body). */
    String skillName();

    /** The {@code pendingAction} discriminator the agent's {@code ResumeController} dispatches on. */
    String flow();

    /** The target noun in its three Russian forms — the only wording a delete flow supplies. */
    Nouns nouns();

    /** Read the candidate pool (own vs personal ∪ shared, which client/method — all lives in the domain). */
    Mono<List<T>> candidates(NormalizedMessage msg);

    /** How to identify / label / describe a candidate for the confirm text + the LLM prompt. */
    CandidateView<T> view();

    /**
     * Perform the terminal mutation (delete / cancel / update). The runner reaches here only on an
     * affirmative resume; it words the success ("Удалил …") / failure ("…возможно, … уже удалена") itself,
     * so this returns nothing. {@code params} carries any extra fields the LLM emitted on selection (the new
     * time for a move), or {@code null} for a plain delete.
     */
    Mono<Void> act(UUID targetId, JsonNode params);

    /**
     * The {@code pendingAction} field the target id is stored under. Defaults to the ADR-0004 standard
     * {@code "targetId"}; the three already-shipped delete flows override to their legacy field names
     * ({@code taskId}/{@code transactionId}/{@code noteId}) so their unit tests stay byte-for-byte. New
     * flows take the default.
     */
    default String idField() {
        return "targetId";
    }

    /** The {@code pendingAction} field the display label is stored under. Standard {@code "label"}; the two
     *  title-keyed delete flows (tasks/notes) override to {@code "title"} for the same test-stability reason. */
    default String labelField() {
        return "label";
    }

    /**
     * Completeness gate: the runner runs this on the single resolved candidate before confirming. A present
     * value is a re-ask (a move without a new time) surfaced as the reply, with no {@code pendingAction} and
     * no action. Delete/cancel are always complete → the default empty. {@code pick} is the parsed LLM
     * selection node, so a move reads its {@code dtstart} here.
     */
    default Optional<String> missing(JsonNode pick) {
        return Optional.empty();
    }

    /** Affirmative words to accept on resume <b>in addition</b> to {@link PickConfirmActRunner#DEFAULT_AFFIRMATIVE}
     *  (notes also treats "забудь" as yes). Default: none. */
    default Set<String> extraAffirmatives() {
        return Set.of();
    }
}
