package dev.fedorov.ailife.agentruntime.web;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared base for an agent's inter-agent action endpoint — the {@code POST /agents/<name>/actions/{action}}
 * envelope the orchestrator forwards an {@link AgentActionRequest} to. Every agent's {@code ActionController}
 * had the same skeleton: reject an unknown action with a structured {@code ok=false}, run the matched
 * handler, and wrap any failure as {@code "<action> failed: <message>"} (logging {@code requestedBy}) so the
 * caller always gets an {@link AgentActionResult}, never an HTTP error.
 *
 * <p>Subclasses stay {@code @RestController}s (the {@code @PostMapping} path carries the agent name, so it
 * must be a per-agent literal): they register each action's handler in their constructor via
 * {@link #register} and delegate the mapped method to {@link #dispatch}. Only the per-action business logic
 * lives in the subclass; the routing + uniform error handling lives here once.
 */
public abstract class AgentActionController {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final String agent;
    private final Map<String, Function<AgentActionRequest, Mono<AgentActionResult>>> handlers =
            new LinkedHashMap<>();

    protected AgentActionController(String agent) {
        this.agent = agent;
    }

    /** Register a handler for one action name (call from the subclass constructor). */
    protected void register(String action, Function<AgentActionRequest, Mono<AgentActionResult>> handler) {
        handlers.put(action, handler);
    }

    /**
     * Route to the registered handler and apply the shared envelope: an unknown action → a structured
     * error; a handler failure → {@code "<action> failed: <message>"} (logged with {@code requestedBy}).
     * A handler that returns {@code Mono.just(AgentActionResult.error(...))} for bad args passes through
     * unwrapped (it is not an error signal).
     */
    protected Mono<AgentActionResult> dispatch(String action, AgentActionRequest request) {
        Function<AgentActionRequest, Mono<AgentActionResult>> handler = handlers.get(action);
        if (handler == null) {
            return Mono.just(AgentActionResult.error(agent + ": unknown action '" + action + "'"));
        }
        return Mono.defer(() -> handler.apply(request))
                .onErrorResume(e -> {
                    log.warn("{} failed (requestedBy={})", action, request.requestingAgent(), e);
                    return Mono.just(AgentActionResult.error(action + " failed: " + e.getMessage()));
                });
    }
}
