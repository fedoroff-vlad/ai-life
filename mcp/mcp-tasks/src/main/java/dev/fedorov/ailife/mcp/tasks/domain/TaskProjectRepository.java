package dev.fedorov.ailife.mcp.tasks.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskProjectRepository extends JpaRepository<TaskProject, UUID> {

    List<TaskProject> findByHouseholdIdOrderByName(UUID householdId);

    List<TaskProject> findByHouseholdIdAndStatusOrderByName(UUID householdId, String status);
}
