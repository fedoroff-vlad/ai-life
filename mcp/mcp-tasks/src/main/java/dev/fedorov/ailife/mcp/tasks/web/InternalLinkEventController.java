package dev.fedorov.ailife.mcp.tasks.web;

import dev.fedorov.ailife.contracts.tasks.LinkTaskToEventInput;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for {@code link_task_to_event} — for deterministic system callers (no
 * LLM tax). {@code POST /internal/link-event} (body {@link LinkTaskToEventInput}) delegates straight
 * to the {@code linkTaskToEvent} tool, so its invariants (required fields, unknown-id guard) apply
 * identically. Used by tasks-agent's task-to-event flow to record the calendar UID after
 * calendar-agent (via orchestrator) created the event. Validation / unknown id → 400. Mirrors
 * {@link InternalClarifyController}.
 */
@RestController
@RequestMapping("/internal/link-event")
public class InternalLinkEventController {

    private final TasksMcpTools tools;

    public InternalLinkEventController(TasksMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> link(@RequestBody LinkTaskToEventInput input) {
        try {
            return ResponseEntity.ok(tools.linkTaskToEvent(input.id(), input.calendarEventUid()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
