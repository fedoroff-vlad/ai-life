package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deterministic write-path rule set (ADR-0002). Mirrors calendar-agent's former inline logic so the
 * retrofit (slice 3) is provably behaviour-preserving.
 */
class SharingResolverTest {

    private final UUID user = UUID.randomUUID();
    private final UUID personal = UUID.randomUUID();
    private final UUID family = UUID.randomUUID();
    private final UUID envelope = UUID.randomUUID();

    private final ProfileSharingClient profile = mock(ProfileSharingClient.class);

    /** Default policy under test: occasions → shared, else private (the calendar rule, as a stand-in). */
    private final DefaultSharingPolicy occasionsShared =
            ctx -> ctx.hasCategory("birthday") ? SharingScope.SHARED : SharingScope.PRIVATE;

    private SharingResolver resolver() {
        return new SharingResolver(profile, occasionsShared);
    }

    private void routingReturns(List<UUID> shared) {
        when(profile.householdRouting(user))
                .thenReturn(Mono.just(new HouseholdRoutingDto(personal, shared)));
    }

    @Test
    void explicitSharedRoutesToFirstFamilyHousehold() {
        routingReturns(List.of(family));
        StepVerifier.create(resolver().resolveHousehold(
                        user, SharingScope.SHARED, SharingContext.ofCategories(List.of()), envelope))
                .expectNext(family)
                .verifyComplete();
    }

    @Test
    void explicitPrivateRoutesToPersonal_ignoringPolicy() {
        routingReturns(List.of(family));
        // categories would default to shared, but the explicit PRIVATE choice must win.
        StepVerifier.create(resolver().resolveHousehold(
                        user, SharingScope.PRIVATE, SharingContext.ofCategories(List.of("birthday")), envelope))
                .expectNext(personal)
                .verifyComplete();
    }

    @Test
    void nullScopeAppliesPolicyDefault_shared() {
        routingReturns(List.of(family));
        StepVerifier.create(resolver().resolveHousehold(
                        user, null, SharingContext.ofCategories(List.of("birthday")), envelope))
                .expectNext(family)
                .verifyComplete();
    }

    @Test
    void nullScopeAppliesPolicyDefault_private() {
        routingReturns(List.of(family));
        StepVerifier.create(resolver().resolveHousehold(
                        user, null, SharingContext.ofCategories(List.of("meeting")), envelope))
                .expectNext(personal)
                .verifyComplete();
    }

    @Test
    void sharedWithNoFamilyDegradesToPersonal() {
        routingReturns(List.of());
        StepVerifier.create(resolver().resolveHousehold(
                        user, SharingScope.SHARED, SharingContext.ofCategories(List.of()), envelope))
                .expectNext(personal)
                .verifyComplete();
    }

    @Test
    void noUserIdFallsBackToEnvelope_withoutCallingProfile() {
        StepVerifier.create(resolver().resolveHousehold(
                        null, SharingScope.SHARED, SharingContext.ofCategories(List.of()), envelope))
                .expectNext(envelope)
                .verifyComplete();
        verify(profile, never()).householdRouting(any());
    }

    @Test
    void unresolvableUserFallsBackToEnvelope() {
        when(profile.householdRouting(user)).thenReturn(Mono.empty()); // profile-service 404
        StepVerifier.create(resolver().resolveHousehold(
                        user, SharingScope.PRIVATE, SharingContext.ofCategories(List.of()), envelope))
                .expectNext(envelope)
                .verifyComplete();
    }

    @Test
    void noUserIdAndNoFallbackResolvesEmpty() {
        StepVerifier.create(resolver().resolveHousehold(
                        null, SharingScope.PRIVATE, SharingContext.ofCategories(List.of()), null))
                .verifyComplete();
    }

    // --- Learning wired (ADR-0002 item 8) ---------------------------------------------------------

    private final SharingLearningClient learning = mock(SharingLearningClient.class);

    private SharingResolver learningResolver(DefaultSharingPolicy policy) {
        return new SharingResolver(profile, policy, learning, "calendar");
    }

    @Test
    void explicitChoiceIsRecordedKeyedByPersonalHousehold() {
        routingReturns(List.of(family));
        when(learning.record(any(), any(), any(), any())).thenReturn(Mono.empty());
        SharingContext ctx = SharingContext.ofCategories(List.of("birthday"));

        StepVerifier.create(learningResolver(occasionsShared)
                        .resolveHousehold(user, SharingScope.SHARED, ctx, envelope))
                .expectNext(family)
                .verifyComplete();

        // The learn signal is the owner's explicit choice, keyed by their personal (stable) household.
        verify(learning).record(personal, "calendar", ctx.signalKey(), SharingScope.SHARED);
    }

    @Test
    void nonExplicitItemIsNotRecorded() {
        routingReturns(List.of(family));
        StepVerifier.create(learningResolver(occasionsShared)
                        .resolveHousehold(user, null, SharingContext.ofCategories(List.of("meeting")), envelope))
                .expectNext(personal)
                .verifyComplete();
        verify(learning, never()).record(any(), any(), any(), any());
    }

    @Test
    void learnedDefaultFlowsThroughTheResolverAndRoutes() {
        routingReturns(List.of(family));
        SharingContext ctx = SharingContext.ofCategories(List.of("meeting")); // static rule → PRIVATE → personal
        // The owner has repeatedly shared this signal profile → the learned SHARED must route to family.
        when(learning.policy(personal, "calendar", ctx.signalKey()))
                .thenReturn(Mono.just(new LearnedSharingPolicyResponse(SharingScope.SHARED, 0.9, 5)));
        DefaultSharingPolicy learned = new LearnedSharingPolicy(occasionsShared, learning, "calendar");

        StepVerifier.create(learningResolver(learned).resolveHousehold(user, null, ctx, envelope))
                .expectNext(family)
                .verifyComplete();
    }

    // --- DS-N confirm-on-ambiguity (ADR-0002 item 8) ---------------------------------------------

    /** A DS-N policy: confident SHARED for "birthday", but abstains on the "ambiguous" signal. */
    private static final DefaultSharingPolicy abstainsOnAmbiguous = new DefaultSharingPolicy() {
        @Override
        public SharingScope decide(SharingContext ctx) {
            return ctx.hasCategory("birthday") ? SharingScope.SHARED : SharingScope.PRIVATE;
        }

        @Override
        public Optional<SharingScope> maybeDecide(SharingContext ctx) {
            return ctx.hasCategory("ambiguous") ? Optional.empty() : Optional.of(decide(ctx));
        }
    };

    @Test
    void ambiguousDefaultResolvesToNeedsConfirm() {
        routingReturns(List.of(family));
        SharingContext ctx = SharingContext.ofCategories(List.of("ambiguous"));
        StepVerifier.create(new SharingResolver(profile, abstainsOnAmbiguous).resolve(user, null, ctx, envelope))
                .assertNext(res -> {
                    assertThat(res).isInstanceOf(SharingResolution.NeedsConfirm.class);
                    var nc = (SharingResolution.NeedsConfirm) res;
                    assertThat(nc.routing().personalHouseholdId()).isEqualTo(personal);
                    assertThat(nc.ctx()).isEqualTo(ctx);
                    assertThat(nc.fallbackHousehold()).isEqualTo(envelope);
                })
                .verifyComplete();
    }

    @Test
    void confidentDefaultResolvesWithoutAsking() {
        routingReturns(List.of(family));
        StepVerifier.create(new SharingResolver(profile, abstainsOnAmbiguous)
                        .resolve(user, null, SharingContext.ofCategories(List.of("meeting")), envelope))
                .assertNext(res -> assertThat(res).isEqualTo(new SharingResolution.Resolved(personal)))
                .verifyComplete();
    }

    @Test
    void explicitChoiceResolvesEvenWhenPolicyWouldAbstain() {
        routingReturns(List.of(family));
        StepVerifier.create(new SharingResolver(profile, abstainsOnAmbiguous)
                        .resolve(user, SharingScope.SHARED, SharingContext.ofCategories(List.of("ambiguous")), envelope))
                .assertNext(res -> assertThat(res).isEqualTo(new SharingResolution.Resolved(family)))
                .verifyComplete();
    }

    @Test
    void unresolvableUserResolvesToFallback_neverAsks() {
        when(profile.householdRouting(user)).thenReturn(Mono.empty()); // profile-404 → no member to key a tally by
        StepVerifier.create(new SharingResolver(profile, abstainsOnAmbiguous)
                        .resolve(user, null, SharingContext.ofCategories(List.of("ambiguous")), envelope))
                .assertNext(res -> assertThat(res).isEqualTo(new SharingResolution.Resolved(envelope)))
                .verifyComplete();
    }

    @Test
    void resolveHouseholdCollapsesNeedsConfirmToFallback() {
        routingReturns(List.of(family));
        StepVerifier.create(new SharingResolver(profile, abstainsOnAmbiguous)
                        .resolveHousehold(user, null, SharingContext.ofCategories(List.of("ambiguous")), envelope))
                .expectNext(envelope)
                .verifyComplete();
    }

    @Test
    void confirmRecordsTheReplyAndPicksTheHousehold() {
        when(learning.record(any(), any(), any(), any())).thenReturn(Mono.empty());
        SharingContext ctx = SharingContext.ofCategories(List.of("ambiguous"));
        var needsConfirm = new SharingResolution.NeedsConfirm(
                new HouseholdRoutingDto(personal, List.of(family)), ctx, envelope);
        SharingResolver resolver = learningResolver(abstainsOnAmbiguous);

        // The owner answers "общее" → the household is the family one, and the reply is learned.
        assertThat(resolver.confirm(needsConfirm, SharingScope.SHARED)).isEqualTo(family);
        verify(learning).record(personal, "calendar", ctx.signalKey(), SharingScope.SHARED);

        // "личное" → personal.
        assertThat(resolver.confirm(needsConfirm, SharingScope.PRIVATE)).isEqualTo(personal);
        verify(learning).record(personal, "calendar", ctx.signalKey(), SharingScope.PRIVATE);
    }

    @Test
    void pickHouseholdIsPureAndDeterministic() {
        assertThat(SharingResolver.pickHousehold(
                new HouseholdRoutingDto(personal, List.of(family)), SharingScope.SHARED)).isEqualTo(family);
        assertThat(SharingResolver.pickHousehold(
                new HouseholdRoutingDto(personal, List.of()), SharingScope.SHARED)).isEqualTo(personal);
        assertThat(SharingResolver.pickHousehold(
                new HouseholdRoutingDto(personal, List.of(family)), SharingScope.PRIVATE)).isEqualTo(personal);
    }
}
