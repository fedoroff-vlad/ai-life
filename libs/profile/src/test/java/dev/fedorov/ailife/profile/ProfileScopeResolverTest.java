package dev.fedorov.ailife.profile;

import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one read-resolution rule (ADR-0005, generalized from #490 FO-3): self → own household-default →
 * family (shared) household-default → empty, with an error soft-failing to empty. Mirrors the branch
 * coverage of the former {@code BriefingComposerTest} family-default cases, now proved once for all domains.
 */
class ProfileScopeResolverTest {

    private final ProfileSharingClient identity = mock(ProfileSharingClient.class);
    private final ProfileScopeResolver resolver = new ProfileScopeResolver(identity);

    private final UUID user = UUID.randomUUID();
    private final UUID envelope = UUID.randomUUID();   // the caller's own household
    private final UUID family = UUID.randomUUID();      // a shared household the member also belongs to

    /** In-memory store keyed by "householdId/ownerId" (ownerId "-" = the household-default). */
    private final Map<String, String> store = new HashMap<>();

    private BiFunction<UUID, UUID, Mono<String>> fetch() {
        return (hh, owner) -> {
            String v = store.get(hh + "/" + (owner == null ? "-" : owner));
            return v == null ? Mono.empty() : Mono.just(v);
        };
    }

    @Test
    void selfProfileWinsAndSkipsIdentity() {
        store.put(envelope + "/" + user, "self");
        store.put(envelope + "/-", "own-default");

        StepVerifier.create(resolver.resolve(user, envelope, fetch()))
                .expectNext("self")
                .verifyComplete();
        verify(identity, never()).householdRouting(any());
    }

    @Test
    void fallsBackToOwnHouseholdDefault() {
        store.put(envelope + "/-", "own-default");

        StepVerifier.create(resolver.resolve(user, envelope, fetch()))
                .expectNext("own-default")
                .verifyComplete();
        verify(identity, never()).householdRouting(any());
    }

    @Test
    void inheritsFamilyDefaultWhenMemberSetNothing() {
        store.put(family + "/-", "family-default");
        when(identity.householdRouting(user)).thenReturn(
                Mono.just(new HouseholdRoutingDto(envelope, List.of(envelope, family))));

        StepVerifier.create(resolver.resolve(user, envelope, fetch()))
                .expectNext("family-default")   // envelope is skipped (already tried); family's default is inherited
                .verifyComplete();
    }

    @Test
    void emptyWhenNothingSetAnywhere() {
        when(identity.householdRouting(user)).thenReturn(
                Mono.just(new HouseholdRoutingDto(envelope, List.of())));

        StepVerifier.create(resolver.resolve(user, envelope, fetch()))
                .verifyComplete();   // empty → caller applies its own default
    }

    @Test
    void nullUserSkipsFamilyLookupAndResolvesEmpty() {
        StepVerifier.create(resolver.resolve(null, envelope, fetch()))
                .verifyComplete();
        verify(identity, never()).householdRouting(any());
    }

    @Test
    void fetchErrorSoftFailsToEmpty() {
        BiFunction<UUID, UUID, Mono<String>> boom = (hh, owner) -> Mono.error(new IllegalStateException("mcp down"));

        StepVerifier.create(resolver.resolve(user, envelope, boom))
                .verifyComplete();   // an error must not bypass the caller's downstream default
    }
}
