package dev.fedorov.ailife.agents.calendar.web;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.brief.BriefResponder;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.calendar.http.CaldavEventClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import dev.fedorov.ailife.sharing.SharingContext;
import dev.fedorov.ailife.sharing.SharingResolver;
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
 *
 * <p><b>Tenant routing (ADR-0002 — the shared sharing capability).</b> The event's household is no longer
 * taken verbatim from the envelope: the item's sharing choice (explicit on the args, else calendar's
 * {@code CalendarSharingPolicy} default — occasions shared, everything else private) is resolved by the
 * shared {@link SharingResolver} against the acting user's {@code household-routing} split to a concrete
 * personal/family {@code household_id}. The routing/fallback rules live once in {@code libs/sharing}; this
 * controller only builds the {@link SharingContext} and hands off. mcp-caldav stays tenant-agnostic — it
 * writes to whatever household it is handed. When the request carries no {@code userId} (or profile-service
 * can't resolve one), the resolver falls back to the envelope household so the pre-membership inter-agent
 * path keeps working. Calendar is the reference implementation of this capability (ADR-0002 slice 3).
 *
 * <p>Also registers the generic <b>{@code brief}</b> read-action (#290, Slice B2-followup): it delegates
 * to the shared {@link BriefResponder} so the coordinator can ask calendar a focused sub-question
 * (upcoming events, birthdays, agenda) and fold the grounded, read-only answer into a multi-domain
 * synthesis. Calendar is the second agent to expose it, after finance.
 */
@RestController
public class ActionController extends AgentActionController {

    private final CaldavEventClient caldav;
    private final SharingResolver sharing;
    private final ObjectMapper json;

    public ActionController(CaldavEventClient caldav, SharingResolver sharing,
                            BriefResponder briefResponder, ObjectMapper json) {
        super("calendar");
        this.caldav = caldav;
        this.sharing = sharing;
        this.json = json;
        register("create_event", this::createEvent);
        // Generic read-only cross-agent query (#290, Slice B): the coordinator can ask calendar a
        // focused sub-question and fold the grounded answer into a multi-domain synthesis.
        register("brief", briefResponder::answer);
    }

    @PostMapping("/agents/calendar/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
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

        if (parsed.summary() == null || parsed.summary().isBlank() || parsed.dtstart() == null) {
            return Mono.just(AgentActionResult.error(
                    "create_event requires summary and dtstart"));
        }

        UUID envelopeHousehold = request.householdId() != null
                ? request.householdId() : parsed.householdId();
        SharingContext ctx = SharingContext.ofCategories(parsed.categories());

        return sharing.resolveHousehold(request.userId(), parsed.sharing(), ctx, envelopeHousehold)
                .flatMap(household -> caldav.createEvent(parsed.withHouseholdId(household))
                        .map(dto -> AgentActionResult.ok(
                                json.createObjectNode().put("eventUid", dto.calendarUid()))))
                .switchIfEmpty(Mono.just(AgentActionResult.error(
                        "create_event requires a resolvable household (userId or householdId)")));
    }
}
