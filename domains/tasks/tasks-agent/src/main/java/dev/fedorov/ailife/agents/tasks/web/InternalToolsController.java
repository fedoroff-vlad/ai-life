package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.tools.ToolDispatcher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * System-level passthrough to invoke a configured mcp-tasks tool by name. Admin-only by
 * convention (no orchestrator routing, no LLM in front) — intended for cron jobs, deploy scripts,
 * and as a testable seam ahead of the LLM-driven intent router (PR58). Mirrors finance-agent's
 * {@code InternalToolsController} (PR34).
 *
 * <p>{@code POST /agents/tasks/internal/tools/{toolName}} takes the raw JSON args object Spring
 * AI's {@code ToolCallback.call(String)} expects, dispatches via {@link ToolDispatcher}, and
 * returns the tool's JSON-stringified result verbatim (no envelope). {@code call} is blocking, so
 * it runs on the bounded-elastic pool to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/agents/tasks/internal/tools")
public class InternalToolsController {

    private final ToolDispatcher dispatcher;

    public InternalToolsController(ToolDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GetMapping
    public List<String> list() {
        return dispatcher.availableToolNames();
    }

    @PostMapping(value = "/{toolName}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> invoke(@PathVariable String toolName,
                                               @RequestBody String jsonArgs) {
        return Mono.fromCallable(() -> dispatcher.dispatch(toolName, jsonArgs))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().body(
                                "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")));
    }
}
