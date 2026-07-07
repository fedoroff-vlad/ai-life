package dev.fedorov.ailife.mcp.coach.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoachProfileRepository extends JpaRepository<CoachProfile, UUID> {

    /** The single coaching vector for a subject (unique on household_id + subject). */
    Optional<CoachProfile> findByHouseholdIdAndSubject(UUID householdId, UUID subject);
}
