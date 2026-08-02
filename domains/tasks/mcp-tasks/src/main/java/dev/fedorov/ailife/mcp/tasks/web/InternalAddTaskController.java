package dev.fedorov.ailife.mcp.tasks.web;

import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for GTD capture — for deterministic system callers (no LLM tax).
 * {@code POST /internal/task} (body {@link AddTaskInput}) delegates straight to the {@code add_task}
 * tool, so every invariant (required fields, inbox status, source default) applies identically. Used by
 * tasks-agent's {@code task-capture} flow (ADR-0002 slice 5) to persist a task under the household the
 * shared {@code SharingResolver} already routed to (personal vs shared) — the deterministic write path an
 * LLM-driven MCP {@code add_task} call cannot take, since the classifier never sees the household id.
 * mcp-tasks stays tenant-agnostic: it writes to whatever household it is handed. Validation failures →
 * 400. Mirrors {@link InternalClarifyController} and mcp-finance's {@code POST /internal/account}.
 */
@RestController
@RequestMapping("/internal/task")
public class InternalAddTaskController {

    private final TasksMcpTools tools;

    public InternalAddTaskController(TasksMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddTaskInput input) {
        try {
            return ResponseEntity.ok(tools.addTask(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
