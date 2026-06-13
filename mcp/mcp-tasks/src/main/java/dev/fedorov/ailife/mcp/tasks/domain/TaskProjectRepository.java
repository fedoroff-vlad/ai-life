package dev.fedorov.ailife.mcp.tasks.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TaskProjectRepository extends JpaRepository<TaskProject, UUID> {

    List<TaskProject> findByHouseholdIdOrderByName(UUID householdId);

    List<TaskProject> findByHouseholdIdAndStatusOrderByName(UUID householdId, String status);

    /**
     * "Stuck" projects for the GTD weekly review: active projects that have no next-action item
     * (no {@code task_item} with status='next'). These are the projects the user should define a
     * next action for. Ordered by name.
     */
    @Query(value = """
            SELECT p.* FROM tasks.task_project p
            WHERE p.household_id = :householdId
              AND p.status = 'active'
              AND NOT EXISTS (
                  SELECT 1 FROM tasks.task_item i
                  WHERE i.project_id = p.id AND i.status = 'next'
              )
            ORDER BY p.name
            """, nativeQuery = true)
    List<TaskProject> findActiveWithoutNextAction(@Param("householdId") UUID householdId);
}
