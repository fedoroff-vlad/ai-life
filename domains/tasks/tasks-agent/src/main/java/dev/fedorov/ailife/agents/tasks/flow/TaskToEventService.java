package dev.fedorov.ailife.agents.tasks.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.tasks.http.LinkEventClient;
import dev.fedorov.ailife.agents.tasks.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.tasks.LinkTaskToEventInput;
import dev.fedorov.ailife.contracts.tasks.TaskToEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The task-to-event chain (Stage 4 / C1 closer): asks calendar-agent — <b>via the
 * orchestrator</b> (agents never call each other directly) — to {@code create_event} for a
 * hard-deadline task, then records the returned UID on the task through mcp-tasks. One
 * direction (task → event); event-done → task-done is later.
 *
 * <p>Always resolves to an {@link AgentActionResult}: a calendar error or a missing
 * {@code eventUid} propagates as {@code ok=false} and no link is written; a link failure
 * <i>after</i> the event was created is surfaced (the event exists, the task just wasn't
 * stamped) so the caller can retry the link.
 */
@Service
public class TaskToEventService {

    private static final Logger log = LoggerFactory.getLogger(TaskToEventService.class);

    private final OrchestratorInvokeClient orchestrator;
    private final LinkEventClient linkEvent;
    private final ObjectMapper json;

    public TaskToEventService(OrchestratorInvokeClient orchestrator,
                              LinkEventClient linkEvent,
                              ObjectMapper json) {
        this.orchestrator = orchestrator;
        this.linkEvent = linkEvent;
        this.json = json;
    }

    public Mono<AgentActionResult> run(TaskToEventRequest req) {
        if (req.taskId() == null || req.householdId() == null
                || req.summary() == null || req.summary().isBlank() || req.dueAt() == null) {
            return Mono.just(AgentActionResult.error(
                    "task-to-event requires taskId, householdId, summary and dueAt"));
        }

        var args = json.createObjectNode()
                .put("summary", req.summary())
                .put("dtstart", req.dueAt().toString());
        var invoke = new AgentActionRequest(
                "calendar", "create_event", req.householdId(), null, "tasks", args);

        return orchestrator.invoke(invoke)
                .flatMap(result -> {
                    if (!result.ok()) {
                        return Mono.just(result); // propagate calendar's error verbatim
                    }
                    JsonNode uidNode = result.result() == null ? null : result.result().get("eventUid");
                    if (uidNode == null || uidNode.asText().isBlank()) {
                        return Mono.just(AgentActionResult.error("calendar returned no eventUid"));
                    }
                    String eventUid = uidNode.asText();
                    return linkEvent.link(new LinkTaskToEventInput(req.taskId(), eventUid))
                            .map(task -> AgentActionResult.ok(json.createObjectNode()
                                    .put("eventUid", eventUid)
                                    .put("taskId", req.taskId().toString())))
                            .onErrorResume(e -> {
                                log.warn("task-to-event: event {} created but link to task {} failed",
                                        eventUid, req.taskId(), e);
                                return Mono.just(AgentActionResult.error(
                                        "event created (" + eventUid + ") but linking to task failed: "
                                                + e.getMessage()));
                            });
                })
                .onErrorResume(e -> {
                    log.warn("task-to-event failed for task {}", req.taskId(), e);
                    return Mono.just(AgentActionResult.error("task-to-event failed: " + e.getMessage()));
                });
    }
}
