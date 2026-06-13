package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manifest endpoint at {@code /agents/tasks/manifest} — orchestrator scrapes this on startup
 * to build its intent-classifier few-shot.
 */
@RestController
public class ManifestController {

    private final AgentManifest manifest;

    public ManifestController(AgentManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping("/agents/tasks/manifest")
    public AgentManifest manifest() {
        return manifest;
    }
}
