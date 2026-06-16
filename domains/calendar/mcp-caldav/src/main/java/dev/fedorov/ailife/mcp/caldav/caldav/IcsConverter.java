package dev.fedorov.ailife.mcp.caldav.caldav;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Categories;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Builds a single-VEVENT VCALENDAR string. Parsing happens in PR10 with full
 * external-calendar import; here we only generate (write-through to Radicale).
 */
@Component
public class IcsConverter {

    private static final String PRODID = "-//ai-life//mcp-caldav 0.0.1//EN";

    public String render(String uid,
                         String summary,
                         String description,
                         String location,
                         Instant dtstart,
                         Instant dtend,
                         String rrule,
                         List<String> categories) {

        Calendar cal = new Calendar();
        cal.add(new ProdId(PRODID));
        cal.add(ImmutableVersion.VERSION_2_0);

        VEvent event = new VEvent();
        event.add(new Uid(uid));
        event.add(new Summary(summary == null ? "" : summary));
        event.add(new DtStart<>(dtstart));

        if (dtend != null) {
            event.add(new DtEnd<>(dtend));
        }
        if (description != null && !description.isBlank()) {
            event.add(new Description(description));
        }
        if (location != null && !location.isBlank()) {
            event.add(new Location(location));
        }
        if (rrule != null && !rrule.isBlank()) {
            event.add(new RRule<>(rrule));
        }
        if (categories != null && !categories.isEmpty()) {
            event.add(new Categories(String.join(",", categories)));
        }

        cal.add(event);
        return cal.toString();
    }
}
