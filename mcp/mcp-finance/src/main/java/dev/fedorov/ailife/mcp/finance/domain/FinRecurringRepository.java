package dev.fedorov.ailife.mcp.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FinRecurringRepository extends JpaRepository<FinRecurring, UUID> {

    /**
     * Filtered list ordered by {@code next_due ASC NULLS LAST} so the agent sees
     * what's due soonest first. accountId / categoryId are optional — null means
     * "do not filter". Same CAST-on-nullable-bind pattern the other native
     * queries use to keep pgjdbc happy.
     */
    @Query(value = """
            SELECT * FROM finance.fin_recurring r
            WHERE r.household_id = :householdId
              AND (CAST(:accountId  AS uuid) IS NULL OR r.account_id  = CAST(:accountId  AS uuid))
              AND (CAST(:categoryId AS uuid) IS NULL OR r.category_id = CAST(:categoryId AS uuid))
            ORDER BY r.next_due ASC NULLS LAST
            """, nativeQuery = true)
    List<FinRecurring> filter(@Param("householdId") UUID householdId,
                              @Param("accountId") UUID accountId,
                              @Param("categoryId") UUID categoryId);
}
