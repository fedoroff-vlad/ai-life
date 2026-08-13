package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Add a participant to a trip roster (plans/travel.md §Trip wallet, #437). {@code tripId} and
 * {@code label} are required. Supply <b>at most one</b> of {@code userId} (a space member) or
 * {@code personId} (a recorded person) — both null is a label-only member. No role/share/weight:
 * the roster is context, not payers who reconcile.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddTripMemberInput(
        UUID tripId,
        UUID userId,
        UUID personId,
        String label) {
}
