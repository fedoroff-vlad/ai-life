package dev.fedorov.ailife.contracts.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * What scheduler-service POSTs to orchestrator to wake an agent. Orchestrator
 * forwards (or routes) this to the {@code agent} keyed by name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentWakeRequest(
        UUID scheduleId,
        UUID householdId,
        String agent,
        String kind,
        JsonNode payload) {
}
