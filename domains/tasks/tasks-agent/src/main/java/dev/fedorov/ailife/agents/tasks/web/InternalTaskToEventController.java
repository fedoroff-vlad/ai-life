package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.flow.TaskToEventService;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.tasks.TaskToEventRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Entry point for the task-to-event chain (Stage 4 / C1). Given a hard-deadline task, drives
 * {@link TaskToEventService} (orchestrator → calendar create_event → link). Internal/admin —
 * not routed via the orchestrator's intent path; the user-facing trigger (auto-offer on a
 * hard-deadline clarify) is a follow-up. Always replies an {@link AgentActionResult}.
 */
@RestController
public class InternalTaskToEventController {

    private final TaskToEventService service;

    public InternalTaskToEventController(TaskToEventService service) {
        this.service = service;
    }

    @PostMapping("/agents/tasks/internal/task-to-event")
    public Mono<AgentActionResult> taskToEvent(@RequestBody TaskToEventRequest request) {
        return service.run(request);
    }
}
