package dev.fedorov.ailife.mcp.tasks.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskItemRepository extends JpaRepository<TaskItem, UUID> {

    /**
     * Filtered list. status/context/projectId/dueBefore are all optional — null means
     * "do not filter on this column". Ordered by due_at ascending (nulls last) then
     * newest-created, so what's due soonest surfaces first while inbox items (no due)
     * still appear. Caller supplies the hard limit.
     *
     * Native SQL with explicit CAST is required: pgjdbc cannot infer the type of a NULL
     * bound parameter inside {@code (:p IS NULL OR col = :p)}, so the JPQL form fails with
     * "could not determine data type of parameter" (same workaround mcp-finance uses).
     */
    @Query(value = """
            SELECT * FROM tasks.task_item t
            WHERE t.household_id = :householdId
              AND (CAST(:status    AS varchar)     IS NULL OR t.status     = CAST(:status    AS varchar))
              AND (CAST(:context   AS varchar)     IS NULL OR t.context    = CAST(:context   AS varchar))
              AND (CAST(:projectId AS uuid)        IS NULL OR t.project_id = CAST(:projectId AS uuid))
              AND (CAST(:dueBefore AS timestamptz) IS NULL OR t.due_at    <  CAST(:dueBefore AS timestamptz))
            ORDER BY t.due_at ASC NULLS LAST, t.created_at DESC
            LIMIT :lim
            """, nativeQuery = true)
    List<TaskItem> filter(@Param("householdId") UUID householdId,
                          @Param("status") String status,
                          @Param("context") String context,
                          @Param("projectId") UUID projectId,
                          @Param("dueBefore") Instant dueBefore,
                          @Param("lim") int limit);
}
