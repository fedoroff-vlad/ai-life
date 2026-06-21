package dev.fedorov.ailife.mcp.wardrobe.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StyleProfileRepository extends JpaRepository<StyleProfile, UUID> {

    /**
     * The profile for a person, treating a null ownerId as the household-default profile.
     * Native SQL with explicit CAST: pgjdbc cannot infer the type of a NULL bound parameter
     * inside an {@code IS NULL} comparison (same workaround mcp-tasks' filter uses).
     */
    @Query(value = """
            SELECT * FROM wardrobe.style_profile p
            WHERE p.household_id = :householdId
              AND ((CAST(:ownerId AS uuid) IS NULL AND p.owner_id IS NULL)
                   OR p.owner_id = CAST(:ownerId AS uuid))
            """, nativeQuery = true)
    Optional<StyleProfile> findForOwner(@Param("householdId") UUID householdId,
                                        @Param("ownerId") UUID ownerId);
}
