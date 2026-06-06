package dev.fedorov.ailife.mcp.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinCategoryRepository extends JpaRepository<FinCategory, UUID> {

    List<FinCategory> findByHouseholdIdOrderByName(UUID householdId);

    Optional<FinCategory> findByHouseholdIdAndNameAndKind(UUID householdId, String name, String kind);
}
