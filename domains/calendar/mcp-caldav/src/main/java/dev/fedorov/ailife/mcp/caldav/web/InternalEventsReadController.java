package dev.fedorov.ailife.mcp.caldav.web;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.ListEventsInput;
import dev.fedorov.ailife.mcp.caldav.tools.CalendarMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST <b>read</b> passthrough for events in a window — the deterministic inter-service read
 * surface (doctrine #201: MCP-SSE is for LLM tool-selection; {@code /internal/*} is the official
 * deterministic surface). Delegates straight to {@link CalendarMcpTools#listEvents} so the
 * read-from-cache behaviour is identical to the MCP tool. First consumer: {@code platform/calendar-web}
 * (the read-only calendar view / per-person ICS feed, #195). Mirrors the write-side
 * {@link InternalEventController} ({@code POST /internal/event}).
 */
@RestController
public class InternalEventsReadController {

    private final CalendarMcpTools tools;

    public InternalEventsReadController(CalendarMcpTools tools) {
        this.tools = tools;
    }

    /**
     * {@code GET /internal/events?householdId=&from=&to=} — events whose start is within
     * {@code [from, to)} for the household, ordered by start ascending. {@code from}/{@code to} are
     * ISO-8601 instants (e.g. {@code 2026-07-01T00:00:00Z}).
     */
    @GetMapping("/internal/events")
    public ResponseEntity<?> list(@RequestParam UUID householdId,
                                  @RequestParam String from,
                                  @RequestParam String to) {
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = Instant.parse(from);
            toInstant = Instant.parse(to);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "from/to must be ISO-8601 instants, e.g. 2026-07-01T00:00:00Z"));
        }
        List<CalendarEventDto> events = tools.listEvents(new ListEventsInput(householdId, fromInstant, toInstant));
        return ResponseEntity.ok(events);
    }
}
