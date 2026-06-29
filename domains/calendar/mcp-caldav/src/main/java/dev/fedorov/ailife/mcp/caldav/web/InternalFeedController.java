package dev.fedorov.ailife.mcp.caldav.web;

import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import dev.fedorov.ailife.contracts.calendar.CreateFeedInput;
import dev.fedorov.ailife.mcp.caldav.feed.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST management of read-only ICS feed tokens (#195) — the deterministic surface (doctrine
 * #201). Mint / resolve / list / revoke; the token is generated server-side. Consumers: {@code
 * calendar-web} resolves a request token via {@code GET /internal/feeds/{token}}; {@code calendar-agent}
 * mints a feed (and lists/revokes) on a user's "give me my calendar link" request.
 */
@RestController
@RequestMapping("/internal/feeds")
public class InternalFeedController {

    private final FeedService feeds;

    public InternalFeedController(FeedService feeds) {
        this.feeds = feeds;
    }

    /** Mint a new feed token for a household (optionally a specific owner). */
    @PostMapping
    public ResponseEntity<?> mint(@RequestBody CreateFeedInput input) {
        try {
            return ResponseEntity.ok(feeds.mint(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Resolve a token → its feed (404 if unknown or revoked). */
    @GetMapping("/{token}")
    public ResponseEntity<CalendarFeedDto> resolve(@PathVariable String token) {
        return feeds.resolve(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** List a household's feeds (incl. revoked), newest first. */
    @GetMapping
    public List<CalendarFeedDto> list(@RequestParam UUID householdId) {
        return feeds.list(householdId);
    }

    /** Revoke a feed by id — 204 on success, 404 if it doesn't exist or is already revoked. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        return feeds.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
