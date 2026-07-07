package dev.fedorov.ailife.mcp.coach.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachIntakeRepository extends JpaRepository<CoachIntake, UUID> {

    List<CoachIntake> findByHouseholdIdAndSubjectOrderByCreatedAtDesc(UUID householdId, UUID subject,
                                                                      Pageable pageable);
}
