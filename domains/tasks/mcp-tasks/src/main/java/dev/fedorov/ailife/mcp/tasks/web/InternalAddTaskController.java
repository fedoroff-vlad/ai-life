package dev.fedorov.ailife.mcp.tasks.web;

import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for GTD capture — for deterministic system callers (no LLM tax).
 * {@code POST /internal/task} (body {@link AddTaskInput}) delegates straight to the {@code add_task}
 * tool, so every invariant (required fields, inbox status, source default) applies identically. Used by
 * tasks-agent's {@code task-capture} flow (ADR-0002 slice 5) to persist a task under the household the
 * shared {@code SharingResolver} already routed to (personal vs shared) — the deterministic write path an
 * LLM-driven MCP {@code add_task} call cannot take, since the classifier never sees the household id.
 * mcp-tasks stays tenant-agnostic: it writes to whatever household it is handed. Validation failures →
 * 400. Mirrors {@link InternalClarifyController} and mcp-finance's {@code POST /internal/account}.
 *
 * <p>{@code DELETE /internal/task/{id}} delegates to the {@code delete_task} tool and returns the deleted
 * row — the deterministic reversal behind the "отмени последнее" undo primitive (road-test #486, Track H):
 * tasks-agent's {@code /actions/undo} calls it to reverse a just-captured task. Unknown id → 404.
 *
 * <p>{@code PUT /internal/task/{id}} delegates to the {@code update_task} tool — the deterministic partial
 * content edit behind tasks-agent's user-facing {@code task-edit} chat flow (road-test #486, Track H.2):
 * patch only the supplied fields (title / note / due, missing fields keep their value). The path id is
 * authoritative (overrides any id in the body). Unknown id → 404. Mirrors mcp-caldav's
 * {@code PUT /internal/event/{id}}.
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

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody UpdateTaskInput input) {
        UpdateTaskInput withId = new UpdateTaskInput(id, input.title(), input.note(), input.context(),
                input.projectId(), input.priority(), input.dueAt(), input.deferUntil());
        try {
            return ResponseEntity.ok(tools.updateTask(withId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(tools.deleteTask(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
