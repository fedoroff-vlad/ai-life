package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Request to mint a read-only ICS feed token (#195). {@code ownerId} is optional — null mints a
 * whole-household feed (the MVP); a concrete user is reserved for future per-person feeds. {@code label}
 * is the human name shown as the calendar's display name (e.g. "Vlad", "Family"). The token itself is
 * generated server-side, never supplied by the caller.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateFeedInput(
        UUID householdId,
        UUID ownerId,
        String label) {
}
