package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
