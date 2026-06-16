package dev.fedorov.ailife.mcp.caldav.domain;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies the result of an upstream CalDAV op to the events_cache mirror.
 * Returns the up-to-date DTO downstream callers will see.
 */
@Service
public class EventMirror {

    private final EventsCacheRepository repo;

    public EventMirror(EventsCacheRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public CalendarEventDto upsert(UUID householdId,
                                   String sourceCalendar,
                                   String calendarUid,
                                   String etag,
                                   String summary,
                                   String description,
                                   String location,
                                   Instant dtstart,
                                   Instant dtend,
                                   String rrule,
                                   List<String> categories,
                                   UUID personId,
                                   String rawIcs) {

        CalendarEvent event = repo
                .findByHouseholdIdAndSourceCalendarAndCalendarUid(householdId, sourceCalendar, calendarUid)
                .orElseGet(() -> new CalendarEvent(UUID.randomUUID(), householdId, sourceCalendar, calendarUid, summary));

        event.setEtag(etag);
        event.setSummary(summary);
        event.setDescription(description);
        event.setLocation(location);
        event.setDtstart(dtstart);
        event.setDtend(dtend);
        event.setRrule(rrule);
        event.setCategories(categories);
        event.setPersonId(personId);
        event.setRawIcs(rawIcs);

        CalendarEvent saved = repo.save(event);
        return toDto(saved);
    }

    @Transactional
    public boolean deleteById(UUID id) {
        return repo.findById(id).map(e -> {
            repo.delete(e);
            return true;
        }).orElse(false);
    }

    public static CalendarEventDto toDto(CalendarEvent e) {
        return new CalendarEventDto(
                e.getId(),
                e.getHouseholdId(),
                e.getSourceCalendar(),
                e.getCalendarUid(),
                e.getSummary(),
                e.getDescription(),
                e.getLocation(),
                e.getDtstart(),
                e.getDtend(),
                e.getRrule(),
                e.getCategories(),
                e.getPersonId());
    }
}
