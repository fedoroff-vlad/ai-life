package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The memory-driven default (ADR-0002 item 8): the learned scope wins only when the tally is deep and
 * decisive enough; thin data, a near-tie, an unseen profile, no household, or a learning outage all defer to
 * the static rule.
 */
class LearnedSharingPolicyTest {

    private final UUID household = UUID.randomUUID();
    private final SharingLearningClient learning = mock(SharingLearningClient.class);

    /** Static delegate under test: occasions → shared, else private. */
    private final DefaultSharingPolicy staticRule =
            ctx -> ctx.hasCategory("birthday") ? SharingScope.SHARED : SharingScope.PRIVATE;

    private final LearnedSharingPolicy policy =
            new LearnedSharingPolicy(staticRule, learning, "calendar");

    private final SharingContext meeting = SharingContext.ofCategories(List.of("meeting")); // static → PRIVATE

    private void learned(SharingScope scope, double confidence, int total) {
        when(learning.policy(eq(household), eq("calendar"), eq(meeting.signalKey())))
                .thenReturn(Mono.just(new LearnedSharingPolicyResponse(scope, confidence, total)));
    }

    @Test
    void deepAndDecisiveLearnedDefaultOverridesTheStaticRule() {
        // Static rule says PRIVATE for a plain meeting; the owner has repeatedly shared it → SHARED wins.
        learned(SharingScope.SHARED, 0.8, 5);
        StepVerifier.create(policy.decideAsync(meeting, household))
                .expectNext(SharingScope.SHARED)
                .verifyComplete();
    }

    @Test
    void thinSampleDefersToStaticRule() {
        learned(SharingScope.SHARED, 1.0, 2); // total < MIN_SAMPLES
        StepVerifier.create(policy.decideAsync(meeting, household))
                .expectNext(SharingScope.PRIVATE)
                .verifyComplete();
    }

    @Test
    void nearTieDefersToStaticRule() {
        learned(SharingScope.SHARED, 0.55, 9); // confidence < MIN_CONFIDENCE
        StepVerifier.create(policy.decideAsync(meeting, household))
                .expectNext(SharingScope.PRIVATE)
                .verifyComplete();
    }

    @Test
    void unseenProfileDefersToStaticRule() {
        when(learning.policy(eq(household), eq("calendar"), eq(meeting.signalKey())))
                .thenReturn(Mono.empty());
        StepVerifier.create(policy.decideAsync(meeting, household))
                .expectNext(SharingScope.PRIVATE)
                .verifyComplete();
    }

    @Test
    void noLearningHouseholdUsesStaticRuleWithoutQuerying() {
        StepVerifier.create(policy.decideAsync(meeting, null))
                .expectNext(SharingScope.PRIVATE)
                .verifyComplete();
    }

    @Test
    void syncDecideAlwaysReturnsTheStaticRule() {
        assertThat(policy.decide(SharingContext.ofCategories(List.of("birthday")))).isEqualTo(SharingScope.SHARED);
        assertThat(policy.decide(meeting)).isEqualTo(SharingScope.PRIVATE);
    }
}
