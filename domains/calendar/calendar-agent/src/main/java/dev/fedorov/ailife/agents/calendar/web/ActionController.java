package dev.fedorov.ailife.agents.calendar.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.calendar.http.CaldavEventClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Inter-agent action endpoint (Stage 4 / C1). The orchestrator forwards an
 * {@link AgentActionRequest} here when another agent invokes calendar (e.g.
 * tasks-agent turning a hard-deadline task into a calendar event). Currently the
 * only action is {@code create_event}, which maps the request's {@code args} to a
 * {@link CreateEventInput} and persists it via mcp-caldav's {@code /internal/event}.
 * Always replies with an {@link AgentActionResult} (never an HTTP error), so the
 * caller gets a structured {@code ok=false} on a bad request.
 */
@RestController
public class ActionController {

    private static final Logger log = LoggerFactory.getLogger(ActionController.class);

    private final CaldavEventClient caldav;
    private final ObjectMapper json;

    public ActionController(CaldavEventClient caldav, ObjectMapper json) {
        this.caldav = caldav;
        this.json = json;
    }

    @PostMapping("/agents/calendar/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        if (!"create_event".equals(action)) {
            return Mono.just(AgentActionResult.error("calendar: unknown action '" + action + "'"));
        }
        return createEvent(request);
    }

    private Mono<AgentActionResult> createEvent(AgentActionRequest request) {
        if (request.args() == null) {
            return Mono.just(AgentActionResult.error("create_event requires args"));
        }
        CreateEventInput parsed;
        try {
            parsed = json.treeToValue(request.args(), CreateEventInput.class);
        } catch (Exception e) {
            return Mono.just(AgentActionResult.error("create_event: bad args — " + e.getMessage()));
        }

        UUID household = request.householdId() != null ? request.householdId() : parsed.householdId();
        if (household == null || parsed.summary() == null || parsed.summary().isBlank()
                || parsed.dtstart() == null) {
            return Mono.just(AgentActionResult.error(
                    "create_event requires householdId, summary and dtstart"));
        }

        // Envelope scope wins over any householdId in args.
        var input = new CreateEventInput(
                household, parsed.summary(), parsed.description(), parsed.location(),
                parsed.dtstart(), parsed.dtend(), parsed.rrule(), parsed.categories(), parsed.personId());

        return caldav.createEvent(input)
                .map(dto -> AgentActionResult.ok(
                        json.createObjectNode().put("eventUid", dto.calendarUid())))
                .onErrorResume(e -> {
                    log.warn("create_event failed (requestedBy={})", request.requestingAgent(), e);
                    return Mono.just(AgentActionResult.error("create_event failed: " + e.getMessage()));
                });
    }
}
