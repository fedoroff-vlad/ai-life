package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TravelProfileRepository extends JpaRepository<TravelProfile, UUID> {

    /**
     * The travel prefs for a person, treating a null ownerId as the household-default. Native SQL
     * with explicit CAST: pgjdbc cannot infer the type of a NULL bound parameter inside an
     * {@code IS NULL} comparison (same workaround mcp-briefing / mcp-creator / mcp-nutrition use).
     */
    @Query(value = """
            SELECT * FROM travel.travel_profile p
            WHERE p.household_id = :householdId
              AND ((CAST(:ownerId AS uuid) IS NULL AND p.owner_id IS NULL)
                   OR p.owner_id = CAST(:ownerId AS uuid))
            """, nativeQuery = true)
    Optional<TravelProfile> findForOwner(@Param("householdId") UUID householdId,
                                         @Param("ownerId") UUID ownerId);
}
