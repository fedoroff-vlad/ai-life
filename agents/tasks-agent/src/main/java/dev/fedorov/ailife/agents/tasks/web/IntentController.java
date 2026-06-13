package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.intent.IntentRouter;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when intent routing selects {@code tasks}. As of PR58 the controller
 * delegates to {@link IntentRouter}: when mcp-tasks tools are wired the router asks the LLM to
 * either invoke a tool (capture/clarify/list via mcp-tasks) or reply directly; otherwise it falls
 * back to a plain LLM chat (the skeleton behaviour). The controller stays thin — it translates the
 * {@link NormalizedMessage} to text and wraps the router's result in an {@link IntentResponse}
 * (propagating the routing turn's model id, preserving the orchestrator's intent contract).
 */
@RestController
@RequestMapping("/agents/tasks")
public class IntentController {

    private final IntentRouter router;
    private final AgentManifest manifest;

    public IntentController(IntentRouter router, AgentManifest manifest) {
        this.router = router;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message.text())
                .map(r -> new IntentResponse(manifest.name(), r.text(), r.llmModel()));
    }
}
