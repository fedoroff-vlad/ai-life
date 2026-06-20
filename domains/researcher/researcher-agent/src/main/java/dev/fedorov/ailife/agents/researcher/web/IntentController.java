package dev.fedorov.ailife.agents.researcher.web;

import dev.fedorov.ailife.agents.researcher.flow.Researcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code researcher}. The agent is a
 * single-purpose specialist, so any routed message is a research request — it runs the cheap-first
 * {@link Researcher} flow (search → fetch → one LLM synthesis) and returns the summary + links.
 */
@RestController
@RequestMapping("/agents/researcher")
public class IntentController {

    private final Researcher researcher;
    private final AgentManifest manifest;

    public IntentController(Researcher researcher, AgentManifest manifest) {
        this.researcher = researcher;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return researcher.research(message)
                .map(r -> new IntentResponse(manifest.name(), r.text(), r.model()));
    }
}
