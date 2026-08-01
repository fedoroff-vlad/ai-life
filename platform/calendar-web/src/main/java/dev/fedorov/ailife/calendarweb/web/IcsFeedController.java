package dev.fedorov.ailife.calendarweb.web;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import dev.fedorov.ailife.calendarweb.http.CalendarReadClient;
import dev.fedorov.ailife.calendarweb.http.FeedResolveClient;
import dev.fedorov.ailife.calendarweb.http.ProfileHouseholdsClient;
import dev.fedorov.ailife.calendarweb.ics.IcsWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-person <b>ICS feed</b> endpoint (#195). {@code GET /ics/{token}.ics} resolves the secret
 * token to a household feed, reads that household's events for a window
 * ({@code [now-pastDays, now+futureDays)}) via {@link CalendarReadClient}, and renders a read-only
 * {@code text/calendar} document a subscribed Apple / Google / Yandex calendar polls.
 *
 * <p><b>Token resolution (track B):</b> first the persistent store (mcp-caldav {@code
 * /internal/feeds/{token}}, minted on demand — no restart to add a member), then a static env-configured
 * feed as a fallback. Unknown either way → 404. Read-only: no write path lives here.
 *
 * <p><b>Per-person filtering (ADR-0001 slice 5 / #295):</b> when the resolved feed carries an
 * {@code ownerId} (a member), the feed serves that member's <b>household set</b> (personal ∪ shared),
 * resolved via profile-service {@code GET /v1/users/{id}/households}. A feed with no owner (the static
 * env feeds, or a legacy whole-household feed) keeps serving its single household. Any profile failure
 * falls back to the feed's own household — the feed never breaks on a profile hiccup.
 */
@RestController
public class IcsFeedController {

    private static final Logger log = LoggerFactory.getLogger(IcsFeedController.class);

    private final CalendarWebProperties props;
    private final CalendarReadClient client;
    private final FeedResolveClient feeds;
    private final ProfileHouseholdsClient profileHouseholds;

    public IcsFeedController(CalendarWebProperties props, CalendarReadClient client,
                            FeedResolveClient feeds, ProfileHouseholdsClient profileHouseholds) {
        this.props = props;
        this.client = client;
        this.feeds = feeds;
        this.profileHouseholds = profileHouseholds;
    }

    @GetMapping("/ics/{token}.ics")
    public Mono<ResponseEntity<String>> feed(@PathVariable String token) {
        return resolve(token).flatMap(opt -> {
            if (opt.isEmpty()) {
                return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
            }
            Resolved feed = opt.get();
            Instant now = Instant.now();
            Instant from = now.minus(Duration.ofDays(props.getPastDays()));
            Instant to = now.plus(Duration.ofDays(props.getFutureDays()));
            return householdSet(feed)
                    .flatMap(households -> client.events(households, from, to))
                    .map(events -> ResponseEntity.ok()
                            .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                            .header("Content-Disposition", "inline; filename=\"" + token + ".ics\"")
                            .body(IcsWriter.render(feed.label(), events)))
                    .onErrorResume(e -> {
                        log.warn("ics feed failed for household {}: {}", feed.householdId(), e.toString());
                        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .body("Calendar source unavailable, try again later."));
                    });
        });
    }

    /**
     * The households this feed serves. A per-person feed ({@code ownerId} set) reads the member's set
     * (personal ∪ shared) from profile-service; an empty/failed lookup falls back to the feed's own
     * household. An owner-less feed (env feed / legacy whole-household) serves just its household.
     */
    private Mono<List<UUID>> householdSet(Resolved feed) {
        if (feed.ownerId() == null) {
            return Mono.just(List.of(feed.householdId()));
        }
        return profileHouseholds.households(feed.ownerId())
                .map(set -> set.isEmpty() ? List.of(feed.householdId()) : set);
    }

    /** Persistent store first (minted feeds), then a static env-configured feed. */
    private Mono<Optional<Resolved>> resolve(String token) {
        return feeds.resolve(token)
                .map(dbFeed -> dbFeed
                        .map(f -> new Resolved(f.householdId(), f.ownerId(), f.label()))
                        .or(() -> props.feedByToken(token)
                                .map(f -> new Resolved(f.getHouseholdId(), null, f.getLabel()))));
    }

    /** A resolved feed reduced to what the renderer needs. {@code ownerId} null = whole-household feed. */
    private record Resolved(UUID householdId, UUID ownerId, String label) {
    }
}
