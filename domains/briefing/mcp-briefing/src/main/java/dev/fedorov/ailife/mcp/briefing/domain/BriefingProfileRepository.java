package dev.fedorov.ailife.mcp.briefing.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BriefingProfileRepository extends JpaRepository<BriefingProfile, UUID> {

    /**
     * The briefing prefs for a person, treating a null ownerId as the household-default. Native SQL
     * with explicit CAST: pgjdbc cannot infer the type of a NULL bound parameter inside an
     * {@code IS NULL} comparison (same workaround mcp-creator / mcp-nutrition / mcp-wardrobe use).
     */
    @Query(value = """
            SELECT * FROM briefing.briefing_profile p
            WHERE p.household_id = :householdId
              AND ((CAST(:ownerId AS uuid) IS NULL AND p.owner_id IS NULL)
                   OR p.owner_id = CAST(:ownerId AS uuid))
            """, nativeQuery = true)
    Optional<BriefingProfile> findForOwner(@Param("householdId") UUID householdId,
                                           @Param("ownerId") UUID ownerId);

    /**
     * Every profile whose morning wake is on. BR-f's scheduler reads this to fan out the per-person
     * digests; until then it has no caller (verified by the integration test only).
     */
    List<BriefingProfile> findByScheduleEnabledTrue();
}
