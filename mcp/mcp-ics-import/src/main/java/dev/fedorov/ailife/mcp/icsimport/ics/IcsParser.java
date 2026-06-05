package dev.fedorov.ailife.mcp.icsimport.ics;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Categories;
import net.fortuna.ical4j.model.property.DateProperty;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Parses an ICS body into a list of {@link ParsedEvent}s and re-renders single-event
 * VCALENDARs for write-through to Radicale. We keep the source UID verbatim — that
 * is how Apple/Google identify recurring instances across syncs.
 */
@Component
public class IcsParser {

    private static final String PRODID = "-//ai-life//mcp-ics-import 0.0.1//EN";

    public List<ParsedEvent> parse(String icsBody) {
        Calendar source;
        try {
            source = new CalendarBuilder().build(
                    new ByteArrayInputStream(icsBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid ICS body: " + e.getMessage(), e);
        }

        List<ParsedEvent> result = new ArrayList<>();
        for (var component : source.getComponents()) {
            if (!(component instanceof VEvent vevent)) {
                continue;
            }
            String uid = vevent.getUid().map(Uid::getValue).orElse(null);
            Instant dtstart = vevent.<Temporal>getStartDate()
                    .map(DateProperty::getDate)
                    .map(IcsParser::toInstant)
                    .orElse(null);
            if (uid == null || dtstart == null) {
                continue;
            }

            Instant dtend = vevent.<Temporal>getEndDate()
                    .map(DateProperty::getDate)
                    .map(IcsParser::toInstant)
                    .orElse(null);

            String summary = Optional.ofNullable(vevent.getSummary()).map(Summary::getValue).orElse("");
            String description = Optional.ofNullable(vevent.getDescription()).map(Description::getValue).orElse(null);
            String location = Optional.ofNullable(vevent.getLocation()).map(Location::getValue).orElse(null);

            Optional<RRule<?>> rruleProp = vevent.getProperty("RRULE");
            String rrule = rruleProp.map(RRule::getValue).orElse(null);

            List<String> categories = vevent.getCategories().stream()
                    .map(Categories::getValue)
                    .flatMap(v -> Arrays.stream(v.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            result.add(new ParsedEvent(uid, summary, description, location,
                    dtstart, dtend, rrule, categories, renderSingle(vevent)));
        }
        return result;
    }

    /** Render a single VEVENT as a stand-alone VCALENDAR body for Radicale PUT. */
    private static String renderSingle(VEvent vevent) {
        Calendar single = new Calendar();
        single.add(new ProdId(PRODID));
        single.add(ImmutableVersion.VERSION_2_0);
        single.add(vevent);
        return single.toString();
    }

    private static Instant toInstant(Temporal t) {
        if (t instanceof Instant i) return i;
        if (t instanceof OffsetDateTime odt) return odt.toInstant();
        if (t instanceof ZonedDateTime zdt) return zdt.toInstant();
        if (t instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        if (t instanceof LocalDate ld) return ld.atStartOfDay(ZoneOffset.UTC).toInstant();
        throw new IllegalStateException("Unsupported temporal " + t.getClass());
    }

    public record ParsedEvent(
            String uid,
            String summary,
            String description,
            String location,
            Instant dtstart,
            Instant dtend,
            String rrule,
            List<String> categories,
            String rawIcs) {
    }

    /** Constructor injection wants a public no-arg ctor; nothing to wire. */
    public IcsParser() {
    }
}
