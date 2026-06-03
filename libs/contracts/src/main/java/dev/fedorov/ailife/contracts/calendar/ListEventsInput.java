package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListEventsInput(UUID householdId, Instant from, Instant to) {
}
