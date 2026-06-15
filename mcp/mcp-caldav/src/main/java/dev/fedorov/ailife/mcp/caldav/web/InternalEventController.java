package dev.fedorov.ailife.mcp.caldav.web;

import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import dev.fedorov.ailife.mcp.caldav.tools.CalendarMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for event creation. Lets another service create an event
 * deterministically — it already has the concrete fields and doesn't need an LLM to
 * pick the tool. First consumer: calendar-agent's {@code create_event} action
 * (Stage 4 / C1, the task-to-event chain). Delegates straight to
 * {@link CalendarMcpTools#createEvent} so the write-through-Radicale + cache-mirror
 * behaviour is identical to the MCP tool. Mirrors mcp-finance's
 * {@code POST /internal/transaction}.
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
}
