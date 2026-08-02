package dev.fedorov.ailife.agents.tasks.read;

import dev.fedorov.ailife.agents.tasks.http.NextActionClient;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskReads} — the sharing-aware read cut (ADR-0002 slice 5b). Mock
 * {@link NextActionClient} + {@link ProfileSharingClient} exercise the own-vs-shared household resolution
 * and the cross-household union. Mirrors finance's {@code SpendingReadsTest}.
 */
class TaskReadsTest {

    private final NextActionClient nextActions = mock(NextActionClient.class);
    private final ProfileSharingClient profileSharing = mock(ProfileSharingClient.class);
    private final TaskReads reads = new TaskReads(nextActions, profileSharing);

    @Test
    void ownCutIsJustTheEnvelopeHouseholdWithoutHittingProfile() {
        UUID envelope = UUID.randomUUID();

        StepVerifier.create(reads.households(envelope, UUID.randomUUID(), false))
                .assertNext(set -> assertThat(set).containsExactly(envelope))
                .verifyComplete();

        // The own cut never asks profile-service for the member's household set.
        verify(profileSharing, never()).households(any());
    }

    @Test
    void sharedCutUnionsThePersonalAndSharedSet() {
        UUID envelope = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();
        when(profileSharing.households(eq(userId))).thenReturn(Mono.just(List.of(envelope, sharedHh)));

        StepVerifier.create(reads.households(envelope, userId, true))
                .assertNext(set -> assertThat(set).containsExactly(envelope, sharedHh))
                .verifyComplete();
    }

    @Test
    void sharedCutWithNoUserOrEmptySetDegradesToTheEnvelope() {
        UUID envelope = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // No acting user → cannot resolve a set → own cut.
        StepVerifier.create(reads.households(envelope, null, true))
                .assertNext(set -> assertThat(set).containsExactly(envelope))
                .verifyComplete();
        // Empty set (unknown user / profile down) → degrade to the envelope so a read never breaks.
        when(profileSharing.households(eq(userId))).thenReturn(Mono.just(List.of()));
        StepVerifier.create(reads.households(envelope, userId, true))
                .assertNext(set -> assertThat(set).containsExactly(envelope))
                .verifyComplete();
    }

    @Test
    void nextActionsUnionFlattensAcrossHouseholdsAndCaps() {
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        when(nextActions.fetchNextActions(eq(h1), anyInt()))
                .thenReturn(Mono.just(List.of(next("h1 a"), next("h1 b"))));
        when(nextActions.fetchNextActions(eq(h2), anyInt()))
                .thenReturn(Mono.just(List.of(next("h2 a"))));

        StepVerifier.create(reads.nextActionsUnion(List.of(h1, h2), 50))
                .assertNext(items -> assertThat(items).extracting(TaskItemDto::title)
                        .containsExactlyInAnyOrder("h1 a", "h1 b", "h2 a"))
                .verifyComplete();
    }

    @Test
    void nextActionsUnionRespectsTheLimitAcrossHouseholds() {
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        when(nextActions.fetchNextActions(eq(h1), anyInt()))
                .thenReturn(Mono.just(List.of(next("a"), next("b"))));
        when(nextActions.fetchNextActions(eq(h2), anyInt()))
                .thenReturn(Mono.just(List.of(next("c"), next("d"))));

        StepVerifier.create(reads.nextActionsUnion(List.of(h1, h2), 3))
                .assertNext(items -> assertThat(items).hasSize(3))
                .verifyComplete();
    }

    private static TaskItemDto next(String title) {
        return new TaskItemDto(UUID.randomUUID(), null, null, null, title, "next",
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }
}
