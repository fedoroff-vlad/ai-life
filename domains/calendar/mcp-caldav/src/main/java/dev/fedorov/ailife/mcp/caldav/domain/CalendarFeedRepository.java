package dev.fedorov.ailife.mcp.caldav.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarFeedRepository extends JpaRepository<CalendarFeed, UUID> {

    /** Resolve a feed by its token, only if it hasn't been revoked. */
    Optional<CalendarFeed> findByTokenAndRevokedAtIsNull(String token);

    /** All feeds for a household (incl. revoked), newest first — for the management list. */
    List<CalendarFeed> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
}
