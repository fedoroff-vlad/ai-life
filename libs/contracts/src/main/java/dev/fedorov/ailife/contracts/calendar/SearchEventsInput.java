package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchEventsInput(UUID householdId, String query) {
}
