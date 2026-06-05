package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PullCalendarResult(
        UUID subscriptionId,
        int eventsUpserted,
        int eventsRemoved,
        String error) {
}
