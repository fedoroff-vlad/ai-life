package dev.fedorov.ailife.agents.tasks.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.tasks.http.DeleteTaskClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Inter-agent action endpoint for tasks (Stage 4 / Track C1 envelope). The orchestrator forwards an
 * {@link AgentActionRequest} here.
 *
 * <p>Registers the reserved <b>{@code undo}</b> action (road-test #486, Track H — tasks is the reference
 * producer): when the owner says "отмени последнее" right after a capture, the orchestrator dispatches the
 * task's stored undo handle here, and this reverses it by deleting the just-captured task via mcp-tasks'
 * {@code DELETE /internal/task/{id}} ({@link DeleteTaskClient}). Returns the agent's own user-facing
 * confirmation ({@code {message}}) naming the deleted task; a missing/already-deleted task → an honest
 * {@code ok=false} the orchestrator surfaces verbatim (never a silent no-op).
 */
@RestController
public class ActionController extends AgentActionController {

    private final DeleteTaskClient deleteTask;
    private final ObjectMapper json;

    public ActionController(DeleteTaskClient deleteTask, ObjectMapper json) {
        super("tasks");
        this.deleteTask = deleteTask;
        this.json = json;
        register("undo", this::undo);
    }

    @PostMapping("/agents/tasks/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
    }

    /** Reverse a captured task: delete it by the stored id and confirm with its title. */
    private Mono<AgentActionResult> undo(AgentActionRequest request) {
        UUID taskId = uuidArg(request, "taskId");
        if (taskId == null) {
            return Mono.just(AgentActionResult.error("undo requires args.taskId"));
        }
        return deleteTask.delete(taskId)
                .map(dto -> {
                    ObjectNode node = json.createObjectNode();
                    node.put("message", "Удалил: «" + dto.title() + "».");
                    return AgentActionResult.ok(node);
                })
                .onErrorResume(e -> Mono.just(AgentActionResult.error(
                        "Не нашёл задачу для отмены — возможно, она уже удалена.")));
    }

    private static UUID uuidArg(AgentActionRequest request, String field) {
        JsonNode args = request.args();
        if (args == null) {
            return null;
        }
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(v.asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
