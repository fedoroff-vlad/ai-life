package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IcsSubscriptionDto(
        UUID id,
        UUID householdId,
        String name,
        String slug,
        String url,
        Instant lastSyncedAt,
        String lastError) {
}
