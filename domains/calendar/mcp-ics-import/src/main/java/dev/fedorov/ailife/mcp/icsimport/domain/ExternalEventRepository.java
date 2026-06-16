package dev.fedorov.ailife.mcp.icsimport.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalEventRepository extends JpaRepository<ExternalEvent, UUID> {

    Optional<ExternalEvent> findByHouseholdIdAndSourceCalendarAndCalendarUid(
            UUID householdId, String sourceCalendar, String calendarUid);

    List<ExternalEvent> findByHouseholdIdAndSourceCalendar(UUID householdId, String sourceCalendar);
}
