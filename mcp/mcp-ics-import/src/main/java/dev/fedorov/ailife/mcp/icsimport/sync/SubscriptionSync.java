package dev.fedorov.ailife.mcp.icsimport.sync;

import dev.fedorov.ailife.contracts.calendar.PullCalendarResult;
import dev.fedorov.ailife.mcp.icsimport.caldav.CalDavWriteClient;
import dev.fedorov.ailife.mcp.icsimport.domain.ExternalEvent;
import dev.fedorov.ailife.mcp.icsimport.domain.ExternalEventRepository;
import dev.fedorov.ailife.mcp.icsimport.domain.IcsSubscription;
import dev.fedorov.ailife.mcp.icsimport.domain.IcsSubscriptionRepository;
import dev.fedorov.ailife.mcp.icsimport.ics.IcsFetcher;
import dev.fedorov.ailife.mcp.icsimport.ics.IcsParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fetch → parse → diff → write-through for one subscription. The cache is the
 * authority for "what was here before"; events present in the prior pull but absent
 * in the new ICS body are deleted from both Radicale and the cache.
 */
@Service
public class SubscriptionSync {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSync.class);

    private final IcsFetcher fetcher;
    private final IcsParser parser;
    private final CalDavWriteClient caldav;
    private final IcsSubscriptionRepository subs;
    private final ExternalEventRepository events;

    public SubscriptionSync(IcsFetcher fetcher,
                            IcsParser parser,
                            CalDavWriteClient caldav,
                            IcsSubscriptionRepository subs,
                            ExternalEventRepository events) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.caldav = caldav;
        this.subs = subs;
        this.events = events;
    }

    @Transactional
    public PullCalendarResult pull(IcsSubscription sub, String sourceCalendar) {
        String body;
        List<IcsParser.ParsedEvent> parsed;
        try {
            body = fetcher.fetch(sub.getUrl());
            parsed = parser.parse(body);
        } catch (RuntimeException e) {
            log.warn("Pull failed for subscription {}: {}", sub.getId(), e.getMessage());
            sub.setLastError(truncate(e.getMessage(), 1000));
            subs.save(sub);
            return new PullCalendarResult(sub.getId(), 0, 0, sub.getLastError());
        }

        List<ExternalEvent> existing = events.findByHouseholdIdAndSourceCalendar(
                sub.getHouseholdId(), sourceCalendar);
        Set<String> incomingUids = new HashSet<>();

        int upserted = 0;
        for (IcsParser.ParsedEvent p : parsed) {
            incomingUids.add(p.uid());
            String etag = caldav.putEvent(sub.getHouseholdId(), sourceCalendar, p.uid(), p.rawIcs());

            ExternalEvent row = events
                    .findByHouseholdIdAndSourceCalendarAndCalendarUid(
                            sub.getHouseholdId(), sourceCalendar, p.uid())
                    .orElseGet(() -> new ExternalEvent(
                            UUID.randomUUID(), sub.getHouseholdId(), sourceCalendar, p.uid(), p.summary()));

            row.setEtag(etag);
            row.setSummary(p.summary());
            row.setDescription(p.description());
            row.setLocation(p.location());
            row.setDtstart(p.dtstart());
            row.setDtend(p.dtend());
            row.setRrule(p.rrule());
            row.setCategories(p.categories());
            row.setRawIcs(p.rawIcs());
            events.save(row);
            upserted++;
        }

        int removed = 0;
        for (ExternalEvent old : existing) {
            if (!incomingUids.contains(old.getCalendarUid())) {
                caldav.deleteEvent(sub.getHouseholdId(), sourceCalendar, old.getCalendarUid());
                events.delete(old);
                removed++;
            }
        }

        sub.setLastSyncedAt(Instant.now());
        sub.setLastError(null);
        subs.save(sub);

        log.info("Sync {} ({}): upserted={} removed={}",
                sub.getId(), sub.getSlug(), upserted, removed);
        return new PullCalendarResult(sub.getId(), upserted, removed, null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
