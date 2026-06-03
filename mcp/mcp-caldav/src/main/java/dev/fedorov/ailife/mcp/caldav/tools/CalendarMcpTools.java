package dev.fedorov.ailife.mcp.caldav.tools;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import dev.fedorov.ailife.contracts.calendar.ListEventsInput;
import dev.fedorov.ailife.contracts.calendar.SearchEventsInput;
import dev.fedorov.ailife.contracts.calendar.UpdateEventInput;
import dev.fedorov.ailife.mcp.caldav.caldav.CalDavClient;
import dev.fedorov.ailife.mcp.caldav.caldav.IcsConverter;
import dev.fedorov.ailife.mcp.caldav.config.McpCaldavProperties;
import dev.fedorov.ailife.mcp.caldav.domain.CalendarEvent;
import dev.fedorov.ailife.mcp.caldav.domain.EventMirror;
import dev.fedorov.ailife.mcp.caldav.domain.EventsCacheRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * MCP tools exposed by this server. Spring AI's MCP starter discovers @Tool-annotated
 * methods on any bean registered through ToolCallbackProvider. All descriptions are in
 * English (token economy — see CLAUDE.md).
 */
@Component
public class CalendarMcpTools {

    private final CalDavClient caldav;
    private final IcsConverter ics;
    private final EventMirror mirror;
    private final EventsCacheRepository repo;
    private final McpCaldavProperties props;

    public CalendarMcpTools(CalDavClient caldav,
                            IcsConverter ics,
                            EventMirror mirror,
                            EventsCacheRepository repo,
                            McpCaldavProperties props) {
        this.caldav = caldav;
        this.ics = ics;
        this.mirror = mirror;
        this.repo = repo;
        this.props = props;
    }

    @Tool(description = """
            Create a new calendar event for a household. Writes the event through to
            Radicale and mirrors it to the local cache. Returns the persisted event,
            including its server-assigned id.
            """)
    public CalendarEventDto createEvent(CreateEventInput input) {
        String uid = UUID.randomUUID().toString();
        String body = ics.render(uid,
                input.summary(), input.description(), input.location(),
                input.dtstart(), input.dtend(), input.rrule(), input.categories());
        String etag = caldav.putEvent(input.householdId(), props.getDefaultCalendar(), uid, body);
        return mirror.upsert(input.householdId(), props.getDefaultCalendar(), uid, etag,
                input.summary(), input.description(), input.location(),
                input.dtstart(), input.dtend(), input.rrule(),
                input.categories(), input.personId(), body);
    }

    @Tool(description = """
            Update an existing calendar event by its internal id. Re-writes the ICS to
            Radicale (PUT is idempotent in CalDAV) and refreshes the cache row. All
            fields except the id may be supplied; missing fields keep their previous
            value.
            """)
    @Transactional
    public CalendarEventDto updateEvent(UpdateEventInput input) {
        CalendarEvent existing = repo.findById(input.id())
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + input.id()));

        String summary = orElse(input.summary(), existing.getSummary());
        String description = orElse(input.description(), existing.getDescription());
        String location = orElse(input.location(), existing.getLocation());
        var dtstart = input.dtstart() != null ? input.dtstart() : existing.getDtstart();
        var dtend = input.dtend() != null ? input.dtend() : existing.getDtend();
        String rrule = orElse(input.rrule(), existing.getRrule());
        List<String> categories = input.categories() != null ? input.categories() : existing.getCategories();

        String body = ics.render(existing.getCalendarUid(),
                summary, description, location, dtstart, dtend, rrule, categories);
        String etag = caldav.putEvent(existing.getHouseholdId(),
                existing.getSourceCalendar(), existing.getCalendarUid(), body);

        return mirror.upsert(existing.getHouseholdId(),
                existing.getSourceCalendar(), existing.getCalendarUid(), etag,
                summary, description, location, dtstart, dtend, rrule,
                categories, existing.getPersonId(), body);
    }

    @Tool(description = """
            Delete an event by its internal id. Issues a CalDAV DELETE then removes
            the cache row. No-op if the event does not exist (returns false).
            """)
    @Transactional
    public boolean deleteEvent(UUID id) {
        return repo.findById(id).map(e -> {
            caldav.deleteEvent(e.getHouseholdId(), e.getSourceCalendar(), e.getCalendarUid());
            mirror.deleteById(id);
            return true;
        }).orElse(false);
    }

    @Tool(description = """
            List events for a household whose start time is within [from, to). Reads
            exclusively from the local cache, so this is a cheap operation that does
            not hit Radicale. Returns events ordered by start time ascending.
            """)
    @Transactional(readOnly = true)
    public List<CalendarEventDto> listEvents(ListEventsInput input) {
        return repo.findInRange(input.householdId(), input.from(), input.to())
                .stream().map(EventMirror::toDto).toList();
    }

    @Tool(description = """
            Fuzzy-match events for a household by summary, using PostgreSQL trigram
            similarity. Useful for "find Маша's birthday" type queries when the exact
            summary is not known. Returns up to 50 best matches.
            """)
    @Transactional(readOnly = true)
    public List<CalendarEventDto> searchEvents(SearchEventsInput input) {
        return repo.searchBySimilarity(input.householdId(), input.query())
                .stream().map(EventMirror::toDto).toList();
    }

    private static String orElse(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }
}
