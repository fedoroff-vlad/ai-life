package dev.fedorov.ailife.mcp.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinGiftBudgetRuleRepository extends JpaRepository<FinGiftBudgetRule, UUID> {

    /** Case-insensitive match (aligns with the functional unique index on lower(relationship)). */
    Optional<FinGiftBudgetRule> findByHouseholdIdAndRelationshipIgnoreCase(UUID householdId, String relationship);

    List<FinGiftBudgetRule> findByHouseholdIdOrderByRelationship(UUID householdId);
}
