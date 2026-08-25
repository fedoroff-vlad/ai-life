package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The domain-supplied seam for a <b>pick → confirm → act</b> flow driven by {@link PickConfirmActRunner}
 * (ADR-0004). A domain implements this small adapter; the runner owns the two-turn orchestration over the
 * Stage-4 pending-action lock, the LLM round-trip + selection parse, the confirm gate, and the resume
 * soft-fail — everything the five copy-pasted flows duplicated.
 *
 * <p>A <b>delete</b> flow supplies {@link #candidates}, {@link #view}, {@link #act} and its {@link #nouns}
 * (the wording comes free from {@link NounPhrasing}). A <b>move/edit</b> flow whose wording doesn't fit the
 * delete template overrides {@link #phrasing} instead, plus {@link #missing} (the "picked a target but a
 * required field is absent → re-ask" gate), {@link #readyToAct} (a resume precondition beyond the id),
 * {@link #decorateUserMessage} (extra LLM context such as {@code now} for relative dates), and reads the
 * extra LLM fields the runner threads through the {@code pendingAction}.
 *
 * @param <T> the candidate domain type (a task, a transaction, a note, an event…)
 */
public interface TargetedActionFlow<T> {

    /** The {@code *-delete}/{@code *-cancel}/{@code *-move} SKILL name — the routing SSOT (SKILL.md body). */
    String skillName();

    /** The {@code pendingAction} discriminator the agent's {@code ResumeController} dispatches on. */
    String flow();

    /** Read the candidate pool (own vs personal ∪ shared, which client/method — all lives in the domain). */
    Mono<List<T>> candidates(NormalizedMessage msg);

    /** How to identify / label / describe a candidate for the confirm text + the LLM prompt. */
    CandidateView<T> view();

    /**
     * Perform the terminal mutation (delete / cancel / update). The runner reaches here only on an
     * affirmative resume; it words the success / failure itself (via {@link #phrasing}), so this returns
     * nothing. {@code pending} is the stored {@code pendingAction} node — a move/edit reads the new time it
     * threaded through it here; a plain delete ignores it.
     */
    Mono<Void> act(UUID targetId, JsonNode pending);

    /**
     * The target noun in its three Russian forms — the only wording a <b>delete</b> flow supplies (it drives
     * the default {@link NounPhrasing}). A flow that overrides {@link #phrasing} need not implement this.
     */
    default Nouns nouns() {
        throw new UnsupportedOperationException(
                "Provide nouns() for the default NounPhrasing, or override phrasing() with custom wording.");
    }

    /** The user-facing wording. Defaults to the delete template driven by {@link #nouns}; a non-delete flow
     *  (calendar cancel/move) returns its own {@link Phrasing}. */
    default Phrasing<T> phrasing() {
        return new NounPhrasing<>(nouns(), view(), labelField());
    }

    /**
     * The {@code pendingAction} field the target id is stored under. Defaults to the ADR-0004 standard
     * {@code "targetId"}; the already-shipped flows override to their legacy field names
     * ({@code taskId}/{@code transactionId}/{@code noteId}/{@code eventId}) so their tests stay byte-for-byte.
     */
    default String idField() {
        return "targetId";
    }

    /** The {@code pendingAction} field the display label is stored under. Standard {@code "label"}; the
     *  title/summary-keyed flows override (tasks/notes → {@code "title"}, calendar → {@code "summary"}). */
    default String labelField() {
        return "label";
    }

    /** Whether a null {@code householdId} short-circuits with {@link Phrasing#noHousehold()}. Delete flows
     *  dereference it in {@link #candidates} so they require it (default); calendar resolves the read set from
     *  the userId instead, so it opts out. */
    default boolean requiresHousehold() {
        return true;
    }

    /**
     * Completeness gate: the runner runs this on the single resolved candidate before confirming. A present
     * value is a re-ask (a move without a new time) surfaced as the reply, with no {@code pendingAction} and
     * no action. Delete/cancel are always complete → the default empty. {@code pick} is the parsed LLM
     * selection node (a move reads its new time here); {@code target} is the resolved candidate (for wording).
     */
    default Optional<String> missing(T target, JsonNode pick) {
        return Optional.empty();
    }

    /**
     * A resume precondition beyond a parseable id — the runner replies {@link Phrasing#notReady()} when this
     * is false. Default: id alone suffices. A move overrides it to also require the stashed new time.
     */
    default boolean readyToAct(JsonNode pending) {
        return true;
    }

    /** Add extra context to the LLM user message (the candidate list is already set). Calendar adds
     *  {@code now} so the model can resolve relative dates; delete flows add nothing (default no-op). */
    default void decorateUserMessage(ObjectNode userMsg) {
    }

    /**
     * <b>Async</b> per-request context merged into the LLM user message before the pick — for data a flow
     * must fetch first (e.g. the household's category list, so the model only ever names an existing
     * category). Runs after {@link #candidates}, before the LLM call; the returned node's fields are merged
     * at top level alongside {@code userText}/{@code candidates}, so it composes with the synchronous
     * {@link #decorateUserMessage}. Default: nothing (an empty {@link Mono}). A flow that needs it should
     * soft-fail internally (degrade to empty) so a context-fetch hiccup doesn't sink the whole edit.
     */
    default Mono<ObjectNode> decorateAsync(NormalizedMessage msg) {
        return Mono.empty();
    }

    /** Affirmative words to accept on resume <b>in addition</b> to {@link PickConfirmActRunner#DEFAULT_AFFIRMATIVE}
     *  (notes: "забудь"; calendar: "отмени"/"перенеси"/…). Default: none. */
    default Set<String> extraAffirmatives() {
        return Set.of();
    }
}
