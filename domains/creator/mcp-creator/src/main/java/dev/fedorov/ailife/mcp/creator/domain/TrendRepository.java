package dev.fedorov.ailife.mcp.creator.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrendRepository extends JpaRepository<Trend, UUID> {

    /** Trends in a household, most recently captured first (Pageable caps the count). */
    List<Trend> findByHouseholdIdOrderByCapturedAtDesc(UUID householdId, Pageable pageable);

    /** Trends attributed to one person in a household, most recently captured first. */
    List<Trend> findByHouseholdIdAndOwnerIdOrderByCapturedAtDesc(UUID householdId, UUID ownerId, Pageable pageable);
}
