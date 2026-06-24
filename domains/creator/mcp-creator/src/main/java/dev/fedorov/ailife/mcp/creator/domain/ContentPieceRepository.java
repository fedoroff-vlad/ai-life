package dev.fedorov.ailife.mcp.creator.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentPieceRepository extends JpaRepository<ContentPiece, UUID> {

    /** Content pieces in a household, most recently created first (Pageable caps the count). */
    List<ContentPiece> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId, Pageable pageable);

    /** Content pieces of one kind (idea | draft) in a household, most recently created first. */
    List<ContentPiece> findByHouseholdIdAndKindOrderByCreatedAtDesc(UUID householdId, String kind, Pageable pageable);
}
