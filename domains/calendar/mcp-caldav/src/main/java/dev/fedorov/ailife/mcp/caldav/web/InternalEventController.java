package dev.fedorov.ailife.mcp.caldav.web;

import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import dev.fedorov.ailife.contracts.calendar.UpdateEventInput;
import dev.fedorov.ailife.mcp.caldav.tools.CalendarMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for event create + update + delete. Lets another service mutate an event
 * deterministically — it already has the concrete fields/id and doesn't need an LLM to
 * pick the tool. First consumers: calendar-agent's {@code create_event} action
 * (Stage 4 / C1, the task-to-event chain), its {@code /actions/undo} reversal
 * (#486/Track H.2, HC-2), and its {@code event-move} chat flow (#486/Track H.2, HC-4).
 * Delegates straight to {@link CalendarMcpTools} so the write-through-Radicale + cache-mirror
 * behaviour is identical to the MCP tools. Mirrors mcp-finance's {@code POST/DELETE /internal/transaction}.
 */
@RestController
@RequestMapping("/internal/event")
public class InternalEventController {

    private final CalendarMcpTools tools;

    public InternalEventController(CalendarMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateEventInput input) {
        try {
            return ResponseEntity.ok(tools.createEvent(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an event by its internal id (the {@code event-move} chat flow, #486/HC-4): patches only the
     * supplied fields (see {@link CalendarMcpTools#updateEvent} — missing fields keep their value), so a
     * reschedule sends just the new {@code dtstart}/{@code dtend}. The path id is authoritative (overrides any
     * id in the body). {@code 200} with the updated event; {@code 404} when the id is unknown.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody UpdateEventInput input) {
        UpdateEventInput withId = new UpdateEventInput(id, input.summary(), input.description(),
                input.location(), input.dtstart(), input.dtend(), input.rrule(), input.categories());
        try {
            return ResponseEntity.ok(tools.updateEvent(withId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete an event by its internal id (the undo reversal behind "отмени последнее", #486/HC-2):
     * {@code 204} when it existed and was removed, {@code 404} when it was already gone — so the caller
     * ({@code calendar-agent}'s {@code /actions/undo}) can surface an honest "не нашёл событие для отмены"
     * rather than pretending it undid something.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return tools.deleteEvent(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
