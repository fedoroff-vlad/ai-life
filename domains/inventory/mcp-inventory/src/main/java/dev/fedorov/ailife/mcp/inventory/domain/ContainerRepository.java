package dev.fedorov.ailife.mcp.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContainerRepository extends JpaRepository<ContainerEntity, UUID> {

    /** What a scanned label resolves to — the opaque printed identity, unique across households. */
    Optional<ContainerEntity> findByQrToken(String qrToken);

    long countByHouseholdId(UUID householdId);

    /**
     * A household's containers, optionally narrowed to one zone and/or status. Native SQL with
     * explicit CAST: pgjdbc cannot infer the type of a NULL bound parameter inside an IS NULL
     * comparison (the same workaround mcp-docs' listRecent uses).
     */
    @Query(value = """
            SELECT * FROM inventory.container c
            WHERE c.household_id = :householdId
              AND (CAST(:zoneId AS uuid) IS NULL OR c.zone_id = CAST(:zoneId AS uuid))
              AND (CAST(:status AS text) IS NULL OR c.status = CAST(:status AS text))
            ORDER BY c.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ContainerEntity> listIn(@Param("householdId") UUID householdId,
                                 @Param("zoneId") UUID zoneId,
                                 @Param("status") String status,
                                 @Param("limit") int limit);
}
