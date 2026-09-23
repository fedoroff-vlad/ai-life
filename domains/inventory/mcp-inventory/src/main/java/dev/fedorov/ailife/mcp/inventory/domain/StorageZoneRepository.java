package dev.fedorov.ailife.mcp.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageZoneRepository extends JpaRepository<StorageZoneEntity, UUID> {

    /** The upsert key: a household's zone resolved by the name the user said, case-insensitively. */
    @Query(value = """
            SELECT * FROM inventory.storage_zone z
            WHERE z.household_id = :householdId AND lower(z.name) = lower(:name)
            LIMIT 1
            """, nativeQuery = true)
    Optional<StorageZoneEntity> findByHouseholdAndName(@Param("householdId") UUID householdId,
                                                       @Param("name") String name);

    List<StorageZoneEntity> findByHouseholdIdOrderByNameAsc(UUID householdId);
}
