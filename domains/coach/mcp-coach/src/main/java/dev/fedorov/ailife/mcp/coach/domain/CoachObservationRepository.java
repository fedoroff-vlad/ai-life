package dev.fedorov.ailife.mcp.coach.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachObservationRepository extends JpaRepository<CoachObservation, UUID> {

    List<CoachObservation> findByHouseholdIdAndSubjectOrderByCreatedAtDesc(UUID householdId, UUID subject,
                                                                           Pageable pageable);

    List<CoachObservation> findByHouseholdIdAndSubjectAndSessionIdOrderByCreatedAtDesc(
            UUID householdId, UUID subject, UUID sessionId);
}
