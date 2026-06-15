package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Body for mcp-tasks' {@code POST /internal/link-event} passthrough — stores the
 * calendar event's UID on a task ("task turned into an event"). Creating the actual
 * event is the agent's job (orchestrator → calendar-agent); this only records the link.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkTaskToEventInput(
        UUID id,
        String calendarEventUid) {
}
