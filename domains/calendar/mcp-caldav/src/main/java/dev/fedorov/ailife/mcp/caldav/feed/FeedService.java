package dev.fedorov.ailife.mcp.caldav.feed;

import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import dev.fedorov.ailife.contracts.calendar.CreateFeedInput;
import dev.fedorov.ailife.mcp.caldav.domain.CalendarFeed;
import dev.fedorov.ailife.mcp.caldav.domain.CalendarFeedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints, resolves, lists and revokes read-only ICS feed tokens (#195). The token is a 32-byte
 * URL-safe secret generated here (never client-supplied) — it sits in the public subscription URL, so it
 * is the access credential. Resolution ignores revoked feeds. mcp-caldav owns the {@code calendar} schema,
 * so the feed store lives here; {@code calendar-web} and {@code calendar-agent} reach it over
 * {@code /internal/feeds}.
 */
@Service
public class FeedService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENC = Base64.getUrlEncoder().withoutPadding();

    private final CalendarFeedRepository repo;

    public FeedService(CalendarFeedRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public CalendarFeedDto mint(CreateFeedInput input) {
        if (input == null || input.householdId() == null) {
            throw new IllegalArgumentException("householdId is required");
        }
        String label = (input.label() == null || input.label().isBlank()) ? "ai-life" : input.label().trim();
        CalendarFeed feed = new CalendarFeed(
                UUID.randomUUID(), input.householdId(), input.ownerId(), newToken(), label, Instant.now());
        return repo.save(feed).toDto();
    }

    /** Resolve a token to its (un-revoked) feed, or empty if unknown/revoked. */
    @Transactional(readOnly = true)
    public Optional<CalendarFeedDto> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repo.findByTokenAndRevokedAtIsNull(token).map(CalendarFeed::toDto);
    }

    @Transactional(readOnly = true)
    public List<CalendarFeedDto> list(UUID householdId) {
        return repo.findByHouseholdIdOrderByCreatedAtDesc(householdId).stream()
                .map(CalendarFeed::toDto).toList();
    }

    /** Revoke a feed by id (idempotent). Returns true if a live feed was revoked. */
    @Transactional
    public boolean revoke(UUID id) {
        return repo.findById(id)
                .filter(f -> f.revokedAt() == null)
                .map(f -> {
                    f.revoke(Instant.now());
                    repo.save(f);
                    return true;
                })
                .orElse(false);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENC.encodeToString(bytes);
    }
}
