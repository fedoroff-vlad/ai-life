package dev.fedorov.ailife.agentruntime.profile;

import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The per-domain tweak the shared {@link PersonalizationProfiler} template needs (ADR-0005): what stays
 * domain-specific after the LLM extract + JSON parse + scope + write soft-fail are lifted out. A domain
 * supplies its {@code *-profiler} SKILL name, a builder that maps the extracted draft into its typed
 * set-input (where any post-step like briefing's geocode lives), the write, and the user-facing reply
 * wording (Russian). Everything else — the deterministic LLM turn, the lenient parse, the
 * {@link dev.fedorov.ailife.profile.ProfileScope} owner resolution, the write orchestration — is shared.
 *
 * @param <I> the domain's set-input (built from the draft)
 * @param <S> the domain's saved profile DTO (returned by the write)
 */
public interface ProfileSpec<I, S> {

    /** The domain's {@code *-profiler} SKILL name (its system prompt), e.g. {@code "briefing-profiler"}. */
    String skillName();

    /**
     * Map the extracted preferences {@code draft} into the domain's set-input. {@code ownerId} is already
     * scoped by the template ({@code null} = household-default, else the speaker). Any domain post-step
     * (briefing geocodes the stated city) lives here. May emit {@link Mono#empty()} to abort with
     * {@link #unparseable()} — but the usual empty/garbage case is caught earlier by the parse.
     */
    Mono<I> build(JsonNode draft, UUID ownerId, NormalizedMessage msg);

    /** Persist the built input (the domain's typed {@code PersonalizationProfileClient.set}). */
    Mono<S> write(I input);

    /** Success reply. {@code household} = the household-default was written; {@code saved} = the stored DTO. */
    String success(boolean household, S saved);

    /** Reply when the LLM output can't be parsed into a preferences object. */
    String unparseable();

    /** Reply when the extract or the write fails. */
    String failure();

    /**
     * Optional why-trace (#485/G2) attached to the <b>success</b> reply only — the payload-free "what I did"
     * line for a write. {@code null} (the default) attaches none; the unparseable/failure replies never
     * carry it. A domain whose profile write is user-visible overrides this (e.g. nutrition:
     * {@code "wrote: updated the diet profile"}).
     */
    default String trace() {
        return null;
    }
}
