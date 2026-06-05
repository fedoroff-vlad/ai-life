package dev.fedorov.ailife.mcp.icsimport.tools;

import dev.fedorov.ailife.contracts.calendar.AddSubscriptionInput;
import dev.fedorov.ailife.contracts.calendar.IcsSubscriptionDto;
import dev.fedorov.ailife.contracts.calendar.PullCalendarResult;
import dev.fedorov.ailife.mcp.icsimport.caldav.CalDavWriteClient;
import dev.fedorov.ailife.mcp.icsimport.config.McpIcsImportProperties;
import dev.fedorov.ailife.mcp.icsimport.domain.ExternalEvent;
import dev.fedorov.ailife.mcp.icsimport.domain.ExternalEventRepository;
import dev.fedorov.ailife.mcp.icsimport.domain.IcsSubscription;
import dev.fedorov.ailife.mcp.icsimport.domain.IcsSubscriptionRepository;
import dev.fedorov.ailife.mcp.icsimport.sync.SubscriptionSync;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only ICS subscriptions (Apple iCloud, Google public calendars, etc.). Each
 * subscription is mirrored into a per-subscription Radicale collection
 * ({@code external-<slug>}) so iOS/macOS CalDAV clients can subscribe to a single
 * Radicale URL and see all external feeds.
 */
@Component
public class IcsImportMcpTools {

    private final IcsSubscriptionRepository subs;
    private final ExternalEventRepository events;
    private final SubscriptionSync sync;
    private final CalDavWriteClient caldav;
    private final McpIcsImportProperties props;

    public IcsImportMcpTools(IcsSubscriptionRepository subs,
                             ExternalEventRepository events,
                             SubscriptionSync sync,
                             CalDavWriteClient caldav,
                             McpIcsImportProperties props) {
        this.subs = subs;
        this.events = events;
        this.sync = sync;
        this.caldav = caldav;
        this.props = props;
    }

    @Tool(description = """
            Register a read-only ICS subscription (Apple iCloud / Google public calendar
            URL) for a household and immediately pull it. The slug is derived from the
            display name; if a subscription with the same slug already exists in the
            household, its URL is updated instead of creating a duplicate. Returns the
            persisted subscription including its assigned slug.
            """)
    @Transactional
    public IcsSubscriptionDto addSubscription(AddSubscriptionInput input) {
        String slug = slugify(input.name());
        IcsSubscription sub = subs.findByHouseholdIdAndSlug(input.householdId(), slug)
                .map(existing -> {
                    existing.setName(input.name());
                    existing.setUrl(input.url());
                    return existing;
                })
                .orElseGet(() -> new IcsSubscription(
                        UUID.randomUUID(), input.householdId(), input.name(), slug, input.url()));
        sub = subs.save(sub);

        sync.pull(sub, sourceCalendar(slug));
        return sub.toDto();
    }

    @Tool(description = """
            Pull (re-sync) one subscription by its internal id: fetch the ICS body, diff
            against the cache, upsert new/changed events into Radicale and the cache,
            remove events that vanished from the feed. Safe to call repeatedly; on
            fetch/parse failure the subscription's last_error is updated and the result
            carries the error string.
            """)
    public PullCalendarResult pullCalendar(UUID subscriptionId) {
        IcsSubscription sub = subs.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Subscription not found: " + subscriptionId));
        return sync.pull(sub, sourceCalendar(sub.getSlug()));
    }

    @Tool(description = """
            List all ICS subscriptions configured for a household, ordered by display
            name. Reads from the local table only.
            """)
    @Transactional(readOnly = true)
    public List<IcsSubscriptionDto> listSubscriptions(UUID householdId) {
        return subs.findByHouseholdIdOrderByName(householdId).stream()
                .map(IcsSubscription::toDto)
                .toList();
    }

    @Tool(description = """
            Remove a subscription and all of its mirrored events: deletes each event
            from Radicale, drops the cached rows, then deletes the subscription row
            itself. Best-effort cleanup of the empty Radicale collection follows.
            Returns true if a row was deleted, false if the id was not found.
            """)
    @Transactional
    public boolean removeSubscription(UUID subscriptionId) {
        return subs.findById(subscriptionId).map(sub -> {
            String calendar = sourceCalendar(sub.getSlug());
            for (ExternalEvent ev : events.findByHouseholdIdAndSourceCalendar(
                    sub.getHouseholdId(), calendar)) {
                try {
                    caldav.deleteEvent(sub.getHouseholdId(), calendar, ev.getCalendarUid());
                } catch (RuntimeException ignored) {
                    // Best-effort: continue cleaning even if a single DELETE fails.
                }
                events.delete(ev);
            }
            try {
                caldav.deleteCollection(sub.getHouseholdId(), calendar);
            } catch (RuntimeException ignored) {
                // collection cleanup is best-effort
            }
            subs.delete(sub);
            return true;
        }).orElse(false);
    }

    private String sourceCalendar(String slug) {
        return props.getCollectionPrefix() + "-" + slug;
    }

    /**
     * Lowercase ASCII slug, hyphen-separated, max 48 chars. Cyrillic is transliterated
     * to its NFKD ASCII shadow when possible; characters with no ASCII form are
     * dropped. Worst case (all non-ASCII) we fall back to the first 8 chars of a UUID
     * so the slug is never empty.
     */
    static String slugify(String name) {
        String normalised = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String ascii = normalised.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (ascii.length() > 48) ascii = ascii.substring(0, 48);
        if (ascii.isBlank()) {
            ascii = "feed-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return ascii;
    }
}
