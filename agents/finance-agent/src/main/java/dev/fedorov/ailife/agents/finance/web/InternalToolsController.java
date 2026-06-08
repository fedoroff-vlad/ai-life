package dev.fedorov.ailife.agents.finance.web;

import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
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
 * System-level passthrough to invoke a configured Spring AI MCP-client tool
 * by name. Closes the consumer-side of PR33: until now the
 * {@link org.springframework.ai.tool.ToolCallbackProvider} bean was wired but
 * had no caller. The endpoint is admin-only by convention (no orchestrator
 * routing, no LLM in front of it) — it's intended for cron jobs, deploy
 * scripts and the future intent-flow PR that puts {@code import_moneypro_csv}
 * behind a user-facing intent.
 *
 * <p>{@code POST /agents/finance/internal/tools/{toolName}} accepts the raw
 * JSON args object Spring AI's {@code ToolCallback.call(String)} contract
 * expects, dispatches via {@link ToolDispatcher}, returns the tool's
 * JSON-stringified result verbatim (no wrapping — the orchestrator side has
 * no need to unwrap a generic envelope).
 *
 * <p>{@code ToolCallback.call} is blocking (the Spring AI SDK uses
 * synchronous reactive types under the hood for SSE); we schedule it on the
 * bounded-elastic pool so the WebFlux event loop stays free. The endpoint
 * stays reactive end-to-end.
 */
@RestController
@RequestMapping("/agents/finance/internal/tools")
public class InternalToolsController {

    private final ToolDispatcher dispatcher;

    public InternalToolsController(ToolDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * {@code GET /agents/finance/internal/tools} — list the tool names the
     * dispatcher knows about. Useful for sanity-checking what the agent
     * actually sees (per-environment configuration drift, etc.).
     */
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
