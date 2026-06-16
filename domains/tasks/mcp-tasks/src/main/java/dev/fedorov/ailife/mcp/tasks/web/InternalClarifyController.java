package dev.fedorov.ailife.mcp.tasks.web;

import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for the GTD clarify transition — for deterministic system callers (no
 * LLM tax). {@code POST /internal/clarify} (body {@link ClarifyTaskInput}) delegates straight to the
 * {@code clarify_task} tool, so every invariant (status whitelist, cross-household project guard,
 * completedAt consistency) applies identically. Used by tasks-agent's {@code inbox-clarify} confirm
 * flow to apply each proposed clarification once the user says "да". Validation failures → 400.
 * Mirrors mcp-finance's {@code POST /internal/transaction}.
 */
@RestController
@RequestMapping("/internal/clarify")
public class InternalClarifyController {

    private final TasksMcpTools tools;

    public InternalClarifyController(TasksMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> clarify(@RequestBody ClarifyTaskInput input) {
        try {
            return ResponseEntity.ok(tools.clarifyTask(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
