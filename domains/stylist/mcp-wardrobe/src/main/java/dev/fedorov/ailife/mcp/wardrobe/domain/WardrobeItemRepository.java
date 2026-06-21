package dev.fedorov.ailife.mcp.wardrobe.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WardrobeItemRepository extends JpaRepository<WardrobeItem, UUID> {

    /** All garments in a household, newest first. */
    List<WardrobeItem> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId);

    /** Garments of one category in a household, newest first. */
    List<WardrobeItem> findByHouseholdIdAndCategoryOrderByCreatedAtDesc(UUID householdId, String category);
}
