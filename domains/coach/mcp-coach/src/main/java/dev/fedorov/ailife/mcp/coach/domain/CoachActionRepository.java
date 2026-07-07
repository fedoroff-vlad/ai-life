package dev.fedorov.ailife.mcp.coach.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachActionRepository extends JpaRepository<CoachAction, UUID> {

    List<CoachAction> findByHouseholdIdAndSubjectOrderByCreatedAtDesc(UUID householdId, UUID subject,
                                                                      Pageable pageable);

    List<CoachAction> findByHouseholdIdAndSubjectAndStatusOrderByCreatedAtDesc(
            UUID householdId, UUID subject, String status, Pageable pageable);
}
