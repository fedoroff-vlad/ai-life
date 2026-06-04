package dev.fedorov.ailife.scheduler.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    @Query("""
            SELECT s FROM Schedule s
            WHERE s.enabled = true
              AND s.nextRunTs <= :now
            ORDER BY s.nextRunTs
            """)
    List<Schedule> findDue(@Param("now") Instant now, Pageable page);

    List<Schedule> findByHouseholdIdOrderByNextRunTs(UUID householdId);
}
