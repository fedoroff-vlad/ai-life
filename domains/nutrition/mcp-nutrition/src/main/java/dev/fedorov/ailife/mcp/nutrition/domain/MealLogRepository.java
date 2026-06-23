package dev.fedorov.ailife.mcp.nutrition.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MealLogRepository extends JpaRepository<MealLog, UUID> {

    /** Meals in a household, most recently eaten first (Pageable caps the count). */
    List<MealLog> findByHouseholdIdOrderByEatenAtDesc(UUID householdId, Pageable pageable);

    /** Meals attributed to one person in a household, most recently eaten first. */
    List<MealLog> findByHouseholdIdAndOwnerIdOrderByEatenAtDesc(UUID householdId, UUID ownerId, Pageable pageable);
}
