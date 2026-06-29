package dev.fedorov.ailife.calendarweb.web;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import dev.fedorov.ailife.calendarweb.http.CalendarReadClient;
import dev.fedorov.ailife.calendarweb.http.FeedResolveClient;
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
 */
@RestController
public class IcsFeedController {

    private static final Logger log = LoggerFactory.getLogger(IcsFeedController.class);

    private final CalendarWebProperties props;
    private final CalendarReadClient client;
    private final FeedResolveClient feeds;

    public IcsFeedController(CalendarWebProperties props, CalendarReadClient client, FeedResolveClient feeds) {
        this.props = props;
        this.client = client;
        this.feeds = feeds;
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
            return client.events(feed.householdId(), from, to)
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

    /** Persistent store first (minted feeds), then a static env-configured feed. */
    private Mono<Optional<Resolved>> resolve(String token) {
        return feeds.resolve(token)
                .map(dbFeed -> dbFeed
                        .map(f -> new Resolved(f.householdId(), f.label()))
                        .or(() -> props.feedByToken(token)
                                .map(f -> new Resolved(f.getHouseholdId(), f.getLabel()))));
    }

    /** A resolved feed reduced to what the renderer needs. */
    private record Resolved(UUID householdId, String label) {
    }
}
