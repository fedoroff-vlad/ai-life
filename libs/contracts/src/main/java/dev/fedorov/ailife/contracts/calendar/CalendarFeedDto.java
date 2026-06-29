package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A read-only ICS feed (#195): the secret {@code token} a household member subscribes their calendar to,
 * plus its scope ({@code householdId} / optional {@code ownerId}) and {@code label}. {@code revokedAt} is
 * non-null once the feed is revoked (then it no longer resolves). The {@code token} is the access secret —
 * it appears in the public subscription URL, so callers must treat it as a credential.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalendarFeedDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String label,
        String token,
        Instant createdAt,
        Instant revokedAt) {
}
