package dev.fedorov.ailife.mcp.coach.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachHypothesisRepository extends JpaRepository<CoachHypothesis, UUID> {

    List<CoachHypothesis> findByHouseholdIdAndSubjectOrderByUpdatedAtDesc(UUID householdId, UUID subject,
                                                                          Pageable pageable);

    List<CoachHypothesis> findByHouseholdIdAndSubjectAndStatusOrderByUpdatedAtDesc(
            UUID householdId, UUID subject, String status, Pageable pageable);
}
