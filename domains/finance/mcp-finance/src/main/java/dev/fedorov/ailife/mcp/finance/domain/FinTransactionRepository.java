package dev.fedorov.ailife.mcp.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FinTransactionRepository extends JpaRepository<FinTransaction, UUID> {

    /**
     * Filtered list. accountId/categoryId/from/to are all optional — null means
     * "do not filter on this column". Newest first. Caller supplies the hard limit;
     * if null the service-side cap of 200 wins to avoid unbounded reads from an LLM
     * caller.
     *
     * Native SQL with explicit CAST is required: pgjdbc cannot infer the type of a
     * NULL bound parameter inside `(:p IS NULL OR col = :p)`, so the JPQL form
     * fails with "could not determine data type of parameter".
     */
    @Query(value = """
            SELECT * FROM finance.fin_transaction t
            WHERE t.household_id = :householdId
              AND (CAST(:accountId  AS uuid)        IS NULL OR t.account_id  = CAST(:accountId  AS uuid))
              AND (CAST(:categoryId AS uuid)        IS NULL OR t.category_id = CAST(:categoryId AS uuid))
              AND (CAST(:fromTs     AS timestamptz) IS NULL OR t.ts >= CAST(:fromTs AS timestamptz))
              AND (CAST(:toTs       AS timestamptz) IS NULL OR t.ts <  CAST(:toTs   AS timestamptz))
            ORDER BY t.ts DESC
            LIMIT :lim
            """, nativeQuery = true)
    List<FinTransaction> filter(@Param("householdId") UUID householdId,
                                @Param("accountId") UUID accountId,
                                @Param("categoryId") UUID categoryId,
                                @Param("fromTs") Instant from,
                                @Param("toTs") Instant to,
                                @Param("lim") int limit);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM FinTransaction t WHERE t.accountId = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") UUID accountId);

    /**
     * Signed sum of amounts for a single category in a half-open time window.
     * Powers get_budget_status: spent = -sumAmountByCategoryInWindow(...) so a
     * positive refund reduces "spent".
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FinTransaction t
            WHERE t.householdId = :householdId
              AND t.categoryId  = :categoryId
              AND t.ts >= :fromTs
              AND t.ts <  :toTs
            """)
    BigDecimal sumAmountByCategoryInWindow(@Param("householdId") UUID householdId,
                                           @Param("categoryId") UUID categoryId,
                                           @Param("fromTs") Instant from,
                                           @Param("toTs") Instant to);

    /**
     * Aggregate spending grouped by (category, currency) in [from, to). Returns
     * Object[]{categoryId, categoryName, currency, signedSum, txCount} so the
     * tool layer can decide what "spent" means per kind without baking sign
     * into the SQL. Filter by category kind is optional — pass null to include
     * every kind. Native SQL with explicit CAST mirrors the filter() pattern
     * captured in {@link #filter} so pgjdbc can infer the bind type.
     */
    @Query(value = """
            SELECT t.category_id, c.name, t.currency,
                   COALESCE(SUM(t.amount), 0) AS signed_sum,
                   COUNT(*)                   AS tx_count
            FROM finance.fin_transaction t
            JOIN finance.fin_category    c ON c.id = t.category_id
            WHERE t.household_id = :householdId
              AND t.ts >= :fromTs
              AND t.ts <  :toTs
              AND (CAST(:kind AS varchar) IS NULL OR c.kind = CAST(:kind AS varchar))
            GROUP BY t.category_id, c.name, t.currency
            ORDER BY ABS(SUM(t.amount)) DESC
            """, nativeQuery = true)
    List<Object[]> spendingByCategory(@Param("householdId") UUID householdId,
                                      @Param("fromTs") Instant from,
                                      @Param("toTs") Instant to,
                                      @Param("kind") String kind);
}
