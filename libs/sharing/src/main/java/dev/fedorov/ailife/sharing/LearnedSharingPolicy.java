package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The memory-driven default-sharing policy (ADR-0002 item 8): a decorator over a domain's static
 * {@link DefaultSharingPolicy} that, for an item with no explicit choice, prefers <b>what the owner has
 * actually done before</b> for this signal profile over the hand-written rule. The learning is a
 * deterministic majority vote in memory-service (DS-1) — never LLM inference, because sharing is a privacy
 * boundary; this class only reads that verdict and decides whether to trust it.
 *
 * <p>Wraps the domain's static policy (the {@code delegate}) so a domain opts in by constructing
 * {@code new LearnedSharingPolicy(new CalendarSharingPolicy(), learningClient, "calendar")} where it used to
 * pass the bare policy — nothing else in the domain changes. The sync {@link #decide} still returns the
 * static rule (the fallback), so any caller that hasn't moved to the async seam is unaffected.
 *
 * <p><b>Trust thresholds.</b> The learned scope wins only when the tally is both deep enough
 * ({@code total ≥ MIN_SAMPLES}) and decisive enough ({@code confidence ≥ MIN_CONFIDENCE}); otherwise (thin
 * data, a near-tie, an unseen profile, or a learning outage) it delegates to the static rule. So learning can
 * only sharpen the default toward the owner's demonstrated habit, never flip it on weak evidence.
 */
public class LearnedSharingPolicy implements DefaultSharingPolicy {

    /** Minimum recorded decisions before the learned default is trusted over the static rule. */
    static final int MIN_SAMPLES = 3;
    /** Minimum winning fraction before the learned default is trusted (a near-tie defers to the static rule). */
    static final double MIN_CONFIDENCE = 0.67;

    private final DefaultSharingPolicy delegate;
    private final SharingLearningClient learning;
    private final String domain;

    public LearnedSharingPolicy(DefaultSharingPolicy delegate, SharingLearningClient learning, String domain) {
        this.delegate = delegate;
        this.learning = learning;
        this.domain = domain;
    }

    /** The static rule — the fallback when nothing is learned, and the answer for sync callers. */
    @Override
    public SharingScope decide(SharingContext ctx) {
        return delegate.decide(ctx);
    }

    /**
     * The learned default when the tally is deep + decisive enough; otherwise the wrapped policy's async
     * decision — which is the static rule for an ordinary policy, but <b>may itself abstain</b> (DS-N) so the
     * confirm path can trigger. With no {@code learningHousehold} (pre-membership path) there is nothing to
     * key a tally by → straight to the wrapped policy.
     */
    @Override
    public Mono<SharingScope> decideAsync(SharingContext ctx, UUID learningHousehold) {
        if (learningHousehold == null) {
            return delegate.decideAsync(ctx, learningHousehold);
        }
        return learning.policy(learningHousehold, domain, ctx.signalKey())
                .filter(LearnedSharingPolicy::trustworthy)
                .map(LearnedSharingPolicyResponse::scope)
                .switchIfEmpty(Mono.defer(() -> delegate.decideAsync(ctx, learningHousehold)));
    }

    private static boolean trustworthy(LearnedSharingPolicyResponse learned) {
        return learned.total() >= MIN_SAMPLES && learned.confidence() >= MIN_CONFIDENCE;
    }
}
