package dev.fedorov.ailife.mcp.coach.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachValueRepository extends JpaRepository<CoachValue, UUID> {

    List<CoachValue> findByHouseholdIdAndSubjectOrderByCreatedAtDesc(UUID householdId, UUID subject);

    List<CoachValue> findByHouseholdIdAndSubjectAndActiveTrueOrderByCreatedAtDesc(UUID householdId, UUID subject);
}
