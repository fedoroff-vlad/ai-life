package dev.fedorov.ailife.calendarweb.web;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import dev.fedorov.ailife.calendarweb.http.CalendarReadClient;
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

import java.time.Duration;
import java.time.Instant;

/**
 * The per-person <b>ICS feed</b> endpoint (#195). {@code GET /ics/{token}.ics} resolves the secret
 * token to a configured household feed, reads that household's events for a window
 * ({@code [now-pastDays, now+futureDays)}) via {@link CalendarReadClient}, and renders a read-only
 * {@code text/calendar} document a subscribed Apple / Google / Yandex calendar polls. Unknown token →
 * 404 (don't reveal whether a token exists beyond that). Read-only: no write path lives here.
 */
@RestController
public class IcsFeedController {

    private static final Logger log = LoggerFactory.getLogger(IcsFeedController.class);

    private final CalendarWebProperties props;
    private final CalendarReadClient client;

    public IcsFeedController(CalendarWebProperties props, CalendarReadClient client) {
        this.props = props;
        this.client = client;
    }

    @GetMapping("/ics/{token}.ics")
    public Mono<ResponseEntity<String>> feed(@PathVariable String token) {
        CalendarWebProperties.Feed feed = props.feedByToken(token).orElse(null);
        if (feed == null || feed.getHouseholdId() == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(props.getPastDays()));
        Instant to = now.plus(Duration.ofDays(props.getFutureDays()));
        return client.events(feed.getHouseholdId(), from, to)
                .map(events -> {
                    String ics = IcsWriter.render(feed.getLabel(), events);
                    return ResponseEntity.ok()
                            .contentType(new MediaType("text", "calendar", java.nio.charset.StandardCharsets.UTF_8))
                            .header("Content-Disposition", "inline; filename=\"" + token + ".ics\"")
                            .body(ics);
                })
                .onErrorResume(e -> {
                    log.warn("ics feed failed for household {}: {}", feed.getHouseholdId(), e.toString());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body("Calendar source unavailable, try again later."));
                });
    }
}
