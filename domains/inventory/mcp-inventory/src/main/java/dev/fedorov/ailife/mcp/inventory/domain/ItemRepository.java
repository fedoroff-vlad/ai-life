package dev.fedorov.ailife.mcp.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<ItemEntity, UUID> {

    /** Insertion order — what a card lists, in the order the things went into the box. */
    List<ItemEntity> findByContainerIdOrderByCreatedAtAsc(UUID containerId);

    /**
     * Trigram/substring search over an item's title + description across a household's containers.
     * Case-insensitive ILIKE accelerated by the gin_trgm_ops index on the same concatenated
     * expression (120-inventory); ranked by trigram similarity, then recency. The join to container
     * is what scopes the search to the household — items carry no household of their own.
     */
    @Query(value = """
            SELECT i.* FROM inventory.item i
              JOIN inventory.container c ON c.id = i.container_id
            WHERE c.household_id = :householdId
              AND (coalesce(i.title, '') || ' ' || coalesce(i.description, '')) ILIKE '%' || :query || '%'
            ORDER BY similarity(
                       coalesce(i.title, '') || ' ' || coalesce(i.description, ''),
                       :query) DESC,
                     i.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ItemEntity> search(@Param("householdId") UUID householdId,
                            @Param("query") String query,
                            @Param("limit") int limit);
}
