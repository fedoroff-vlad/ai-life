package dev.fedorov.ailife.mcp.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FinBudgetRepository extends JpaRepository<FinBudget, UUID> {

    /**
     * The currently-active budget (validTo IS NULL) for a (household, category,
     * period) slot. Guarded by partial unique index uq_fin_budget_active so at
     * most one row can be returned.
     */
    @Query("""
            SELECT b FROM FinBudget b
            WHERE b.householdId = :householdId
              AND b.categoryId  = :categoryId
              AND b.period      = :period
              AND b.validTo IS NULL
            """)
    Optional<FinBudget> findActive(@Param("householdId") UUID householdId,
                                   @Param("categoryId") UUID categoryId,
                                   @Param("period") String period);
}
