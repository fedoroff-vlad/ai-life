package dev.fedorov.ailife.agents.finance.read;

import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The finance sharing-aware read helper (ADR-0002 slice 4a) — the single home of the personal-vs-shared
 * cut and the cross-household merge every finance read flow shares. Pure unit test: {@link SpendingClient}
 * and {@link ProfileSharingClient} are mocked.
 */
class SpendingReadsTest {

    private final SpendingClient spending = mock(SpendingClient.class);
    private final ProfileSharingClient profile = mock(ProfileSharingClient.class);
    private final SpendingReads reads = new SpendingReads(spending, profile);

    private static final UUID ENVELOPE = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-04-01T00:00:00Z");

    @Test
    void personalCutIsJustTheEnvelopeHouseholdAndNeverAsksProfile() {
        List<UUID> set = reads.households(ENVELOPE, UUID.randomUUID(), false).block();

        assertThat(set).containsExactly(ENVELOPE);
        verify(profile, never()).households(any());
    }

    @Test
    void sharedCutIsTheProfileUnion() {
        UUID user = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        when(profile.households(eq(user))).thenReturn(Mono.just(List.of(personal, family)));

        assertThat(reads.households(ENVELOPE, user, true).block())
                .containsExactly(personal, family);
    }

    @Test
    void sharedCutDegradesToTheEnvelopeWhenTheProfileSetIsEmpty() {
        UUID user = UUID.randomUUID();
        when(profile.households(eq(user))).thenReturn(Mono.just(List.of()));

        assertThat(reads.households(ENVELOPE, user, true).block())
                .containsExactly(ENVELOPE);
    }

    @Test
    void sharedCutWithNoUserFallsBackToTheEnvelope() {
        assertThat(reads.households(ENVELOPE, null, true).block())
                .containsExactly(ENVELOPE);
        verify(profile, never()).households(any());
    }

    @Test
    void spendingUnionSumsSharedCategoriesAndKeepsDistinctOnesSeparate() {
        UUID hhA = UUID.randomUUID();
        UUID hhB = UUID.randomUUID();
        UUID food = UUID.randomUUID();
        UUID transport = UUID.randomUUID();
        // Food appears in both households → must merge; Transport only in B → must survive on its own.
        when(spending.spendingByCategory(eq(hhA), any(), any())).thenReturn(Mono.just(List.of(
                new SpendingByCategoryRow(food, "Food", "EUR", new BigDecimal("100.00"), 5))));
        when(spending.spendingByCategory(eq(hhB), any(), any())).thenReturn(Mono.just(List.of(
                new SpendingByCategoryRow(food, "Food", "EUR", new BigDecimal("30.00"), 2),
                new SpendingByCategoryRow(transport, "Transport", "EUR", new BigDecimal("40.00"), 3))));

        List<SpendingByCategoryRow> merged = reads.spendingUnion(List.of(hhA, hhB), FROM, TO).block();

        assertThat(merged).hasSize(2);
        SpendingByCategoryRow foodRow = merged.stream().filter(r -> r.categoryId().equals(food)).findFirst().orElseThrow();
        assertThat(foodRow.spent()).isEqualByComparingTo("130.00");
        assertThat(foodRow.txCount()).isEqualTo(7);
        SpendingByCategoryRow transportRow = merged.stream().filter(r -> r.categoryId().equals(transport)).findFirst().orElseThrow();
        assertThat(transportRow.spent()).isEqualByComparingTo("40.00");
        assertThat(transportRow.txCount()).isEqualTo(3);
    }

    @Test
    void spendingUnionOverASingleHouseholdIsThePlainRead() {
        UUID hh = UUID.randomUUID();
        UUID food = UUID.randomUUID();
        when(spending.spendingByCategory(eq(hh), any(), any())).thenReturn(Mono.just(List.of(
                new SpendingByCategoryRow(food, "Food", "EUR", new BigDecimal("100.00"), 5))));

        List<SpendingByCategoryRow> rows = reads.spendingUnion(List.of(hh), FROM, TO).block();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).spent()).isEqualByComparingTo("100.00");
        assertThat(rows.get(0).txCount()).isEqualTo(5);
    }
}
