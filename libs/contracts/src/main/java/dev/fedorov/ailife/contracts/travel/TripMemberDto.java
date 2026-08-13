package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * One participant on a trip roster (plans/travel.md §Trip wallet, #437). Roster/context only — there is
 * no share/weight and no "who owes whom" (settlement is cut). A member carries <b>at most one</b>
 * identity ref — either a space {@code userId} ({@code core.users}) or a recorded {@code personId}
 * ({@code core.people}), or neither (label-only) — plus a required {@code label}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripMemberDto(
        UUID id,
        UUID tripId,
        UUID userId,
        UUID personId,
        String label,
        Instant createdAt) {
}
