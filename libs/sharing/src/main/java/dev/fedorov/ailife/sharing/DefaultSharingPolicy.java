package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * The per-domain extension point of the sharing capability (ADR-0002): given the neutral
 * {@link SharingContext}, decide the <b>default</b> privacy of an item when the author made no explicit
 * choice. Implemented once per domain, in that domain's {@code sharing/} package (e.g.
 * {@code CalendarSharingPolicy}: occasions → shared, everything else → private; {@code FinanceSharingPolicy}:
 * joint-account → shared, else private).
 *
 * <p>This is the <b>only</b> seam where "what is shared here" lives — the routing mechanism itself
 * ({@link SharingResolver}) is shared and deterministic. A domain plugs a static deterministic
 * {@link #decide(SharingContext)} rule; the <i>same interface</i> can be backed by a memory-driven
 * implementation ({@link LearnedSharingPolicy}, ADR-0002 item 8) — learn the owner's past choices — without
 * touching {@link SharingResolver} or any domain.
 *
 * <p><b>The async seam (item 8).</b> A learned default needs an I/O read scoped to the acting member, so the
 * resolver awaits {@link #decideAsync(SharingContext, UUID)} rather than the sync {@link #decide}. The
 * default implementation just wraps {@code decide}, so every static per-domain policy keeps implementing only
 * the sync method and is unchanged; a {@link LearnedSharingPolicy} overrides {@code decideAsync} to consult
 * the tally and falls back to the wrapped static rule. {@code learningHousehold} is the member's personal
 * household — the stable per-member key the tally is scoped by (only the resolver knows it, from the routing
 * read); static policies ignore it.
 */
@FunctionalInterface
public interface DefaultSharingPolicy {

    /** The default scope for an item with no explicit choice. Never returns {@code null}. */
    SharingScope decide(SharingContext ctx);

    /**
     * The <b>abstain-aware</b> default (ADR-0002 item 8, DS-N): the scope for an unspecified item, or
     * {@link Optional#empty()} when the policy genuinely can't tell and would rather the caller <i>ask</i>
     * than default silently. The default implementation is always confident (wraps {@link #decide}), so every
     * existing static policy is unchanged; a DS-N domain overrides this to abstain on its ambiguous case
     * (e.g. docs: an untyped document; tasks: no household-context signal). {@link #decide} must still return
     * a concrete safe fallback for sync callers that never ask.
     */
    default Optional<SharingScope> maybeDecide(SharingContext ctx) {
        return Optional.of(decide(ctx));
    }

    /**
     * The async form the resolver awaits (ADR-0002 item 8). Empty completion means <b>abstain</b> (DS-N) —
     * the resolver turns that into a {@link SharingResolution.NeedsConfirm}. Defaults to
     * {@link #maybeDecide}, so a static policy needs no change; {@link LearnedSharingPolicy} overrides it to
     * prefer the learned default and fall back to the wrapped policy's async form (which may itself abstain).
     *
     * @param ctx               the neutral item signals
     * @param learningHousehold the acting member's personal household (the tally scope), or {@code null} on
     *                          the pre-membership path — ignored by static policies
     */
    default Mono<SharingScope> decideAsync(SharingContext ctx, UUID learningHousehold) {
        return Mono.justOrEmpty(maybeDecide(ctx));
    }

    /** A policy that defaults everything to private — the safe fallback for a domain with no rule yet. */
    static DefaultSharingPolicy privateByDefault() {
        return ctx -> SharingScope.PRIVATE;
    }
}
