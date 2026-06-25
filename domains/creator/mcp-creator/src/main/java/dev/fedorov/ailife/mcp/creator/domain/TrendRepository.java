package dev.fedorov.ailife.mcp.creator.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrendRepository extends JpaRepository<Trend, UUID> {

    /** Trends in a household, most recently captured first (Pageable caps the count). */
    List<Trend> findByHouseholdIdOrderByCapturedAtDesc(UUID householdId, Pageable pageable);

    /** Trends attributed to one person in a household, most recently captured first. */
    List<Trend> findByHouseholdIdAndOwnerIdOrderByCapturedAtDesc(UUID householdId, UUID ownerId, Pageable pageable);

    /**
     * An already-cached trend with the same (household, owner, url) — the dedup key for the gather
     * persist, so re-running a gather doesn't pile up duplicate rows. Native SQL with explicit CAST:
     * pgjdbc cannot infer the type of a NULL bound parameter inside an {@code IS NULL} comparison (the
     * household-default track binds a null owner — same workaround {@code findForOwner} uses).
     */
    @Query(value = """
            SELECT * FROM creator.trend t
            WHERE t.household_id = :householdId
              AND ((CAST(:ownerId AS uuid) IS NULL AND t.owner_id IS NULL)
                   OR t.owner_id = CAST(:ownerId AS uuid))
              AND t.url = :url
            ORDER BY t.captured_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Trend> findForUrl(@Param("householdId") UUID householdId,
                               @Param("ownerId") UUID ownerId,
                               @Param("url") String url);
}
