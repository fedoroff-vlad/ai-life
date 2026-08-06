package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The deterministic <b>write-path</b> engine of the sharing capability (ADR-0002): resolve which concrete
 * {@code household_id} a new item must be written to. This is the single home of the rule set that used to
 * live inline in calendar-agent — every domain now shares it and adds only its {@link DefaultSharingPolicy}.
 *
 * <p>The rule, in order:
 * <ol>
 *   <li>An explicit {@link SharingScope} on the create-input wins; otherwise the domain's
 *       {@link DefaultSharingPolicy#decide(SharingContext)} chooses the default.</li>
 *   <li>With no {@code userId} (the pre-membership inter-agent path), route to the caller-supplied
 *       {@code fallbackHousehold} (the envelope household) unchanged.</li>
 *   <li>Otherwise ask profile-service for the routing split and pick: {@code SHARED} → the first shared
 *       household when one exists, else personal; {@code PRIVATE} → personal. A shared choice with no
 *       family household <b>degrades to personal</b> — the single-owner reality where no shared space
 *       exists yet.</li>
 *   <li>If profile-service can't resolve the user (404 → empty routing), fall back to
 *       {@code fallbackHousehold}.</li>
 * </ol>
 *
 * <p>The mechanism is deterministic on purpose — it is a privacy boundary. Only step 1's default (behind
 * the policy seam) is judgement; the household pick and fallbacks never guess.
 *
 * <p><b>Learning (ADR-0002 item 8, optional).</b> When wired with a {@link SharingLearningClient} + a
 * {@code domain}, the resolver records the owner's <b>explicit</b> choice as the learn signal (keyed by their
 * personal household — a stable per-member key, so SHARED and PRIVATE choices for the same signal profile are
 * compared, not split across the households they land in), best-effort. For a non-explicit item it awaits the
 * policy's async form, letting a {@link LearnedSharingPolicy} read that tally. Without learning wired (the
 * two-arg constructor) the resolver behaves exactly as before.
 *
 * <p><b>Confirm-on-ambiguity (ADR-0002 item 8, DS-N).</b> {@link #resolveHousehold} always yields a
 * household. {@link #resolve} instead yields a {@link SharingResolution}: normally {@code Resolved}, but a
 * {@code NeedsConfirm} when the default is genuinely ambiguous — the learned tally is not confident <i>and</i>
 * the domain policy abstains ({@link DefaultSharingPolicy#maybeDecide} / an empty {@code decideAsync}). A
 * caller that wants to ask uses {@code resolve}, defers the write on {@code NeedsConfirm}, and calls
 * {@link #confirm} with the owner's reply to finish + learn; a caller that never asks stays on
 * {@code resolveHousehold}, which collapses {@code NeedsConfirm} to the fallback.
 */
public class SharingResolver {

    private final ProfileSharingClient profile;
    private final DefaultSharingPolicy policy;
    private final SharingLearningClient learning;
    private final String domain;

    /** Learning off — the resolver behaves exactly as the deterministic engine did before item 8. */
    public SharingResolver(ProfileSharingClient profile, DefaultSharingPolicy policy) {
        this(profile, policy, null, null);
    }

    /**
     * Learning wired: explicit choices are recorded (keyed by the member's personal household) and a
     * {@link LearnedSharingPolicy} may read the tally on the non-explicit path.
     *
     * @param learning the tally client, or {@code null} to disable recording
     * @param domain   the domain name the tally is grouped by (e.g. {@code "calendar"})
     */
    public SharingResolver(ProfileSharingClient profile, DefaultSharingPolicy policy,
                           SharingLearningClient learning, String domain) {
        this.profile = profile;
        this.policy = policy;
        this.learning = learning;
        this.domain = domain;
    }

    /**
     * Resolve the concrete household to write an item into.
     *
     * @param userId            the acting user, or {@code null} on the pre-membership inter-agent path
     * @param explicitScope     the author's explicit choice, or {@code null} to apply the domain policy
     * @param ctx               the neutral signals the policy reads when {@code explicitScope} is absent
     * @param fallbackHousehold the envelope household to use when there is no {@code userId} or the user
     *                          can't be resolved (may be {@code null} → {@link Mono#empty()})
     * @return the resolved {@code household_id}, or {@link Mono#empty()} when nothing resolves
     */
    public Mono<UUID> resolveHousehold(UUID userId, SharingScope explicitScope, SharingContext ctx,
                                       UUID fallbackHousehold) {
        return resolve(userId, explicitScope, ctx, fallbackHousehold)
                .mapNotNull(res -> switch (res) {
                    case SharingResolution.Resolved r -> r.household();
                    // Callers that don't ask degrade an ambiguous default to the envelope household — the same
                    // fallback the engine already uses when a user can't be resolved. Unreachable unless the
                    // domain's policy abstains (DS-N); an asking caller uses resolve(...) instead.
                    case SharingResolution.NeedsConfirm nc -> nc.fallbackHousehold();
                });
    }

    /**
     * Resolve a create to either a concrete household ({@link SharingResolution.Resolved}) or, when the
     * default is genuinely ambiguous (the policy abstains — DS-N), a {@link SharingResolution.NeedsConfirm}
     * the caller can defer + ask on. An explicit choice always resolves (and is recorded); the no-{@code
     * userId} / profile-404 fallbacks resolve to {@code fallbackHousehold} (never ask — there is no member to
     * key a tally by).
     */
    public Mono<SharingResolution> resolve(UUID userId, SharingScope explicitScope, SharingContext ctx,
                                           UUID fallbackHousehold) {
        if (userId == null) {
            return Mono.just(new SharingResolution.Resolved(fallbackHousehold));
        }
        return profile.householdRouting(userId)
                .<SharingResolution>flatMap(routing -> {
                    if (explicitScope != null) {
                        recordDecision(routing.personalHouseholdId(), ctx, explicitScope);
                        return Mono.just(new SharingResolution.Resolved(pickHousehold(routing, explicitScope)));
                    }
                    return policy.decideAsync(ctx, routing.personalHouseholdId())
                            .<SharingResolution>map(scope ->
                                    new SharingResolution.Resolved(pickHousehold(routing, scope)))
                            .defaultIfEmpty(new SharingResolution.NeedsConfirm(routing, ctx, fallbackHousehold));
                })
                .switchIfEmpty(Mono.just(new SharingResolution.Resolved(fallbackHousehold)));
    }

    /**
     * Finish a deferred write once the owner has answered the confirm (DS-N): record their now-explicit choice
     * as the learn signal (keyed by their personal household, so the next similar item can default without
     * asking) and pick the concrete household. Same record + pick the resolver runs on the explicit path.
     */
    public UUID confirm(SharingResolution.NeedsConfirm needsConfirm, SharingScope chosen) {
        recordDecision(needsConfirm.routing().personalHouseholdId(), needsConfirm.ctx(), chosen);
        return pickHousehold(needsConfirm.routing(), chosen);
    }

    /**
     * Best-effort learn signal: record the owner's explicit choice for this signal profile. Fire-and-forget —
     * it must never delay or fail the create — and a no-op unless learning is wired and a personal household
     * resolved.
     */
    private void recordDecision(UUID personalHouseholdId, SharingContext ctx, SharingScope scope) {
        if (learning == null || personalHouseholdId == null) {
            return;
        }
        learning.record(personalHouseholdId, domain, ctx.signalKey(), scope).subscribe();
    }

    /**
     * SHARED → the first family household when the user has one, else personal; PRIVATE → personal.
     * A shared choice with no family household degrades to personal.
     */
    static UUID pickHousehold(HouseholdRoutingDto routing, SharingScope scope) {
        List<UUID> shared = routing.sharedHouseholdIds();
        if (scope == SharingScope.SHARED && shared != null && !shared.isEmpty()) {
            return shared.get(0);
        }
        return routing.personalHouseholdId();
    }
}
