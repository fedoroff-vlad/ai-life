package dev.fedorov.ailife.mcp.caldav.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventsCacheRepository extends JpaRepository<CalendarEvent, UUID> {

    Optional<CalendarEvent> findByHouseholdIdAndSourceCalendarAndCalendarUid(
            UUID householdId, String sourceCalendar, String calendarUid);

    @Query("""
            SELECT e FROM CalendarEvent e
            WHERE e.householdId = :householdId
              AND e.dtstart >= :from
              AND e.dtstart < :to
            ORDER BY e.dtstart
            """)
    List<CalendarEvent> findInRange(@Param("householdId") UUID householdId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    /**
     * Trigram-backed fuzzy search on summary. The {@code pg_trgm} extension was
     * installed by infra/postgres/init.sql; we lean on the SIMILARITY operator.
     */
    @Query(value = """
            SELECT *
              FROM calendar.events_cache
             WHERE household_id = :householdId
               AND summary % :query
             ORDER BY similarity(summary, :query) DESC
             LIMIT 50
            """, nativeQuery = true)
    List<CalendarEvent> searchBySimilarity(@Param("householdId") UUID householdId,
                                           @Param("query") String query);
}
