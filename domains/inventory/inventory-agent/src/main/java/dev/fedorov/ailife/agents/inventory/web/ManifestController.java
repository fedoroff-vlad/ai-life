package dev.fedorov.ailife.agents.inventory.web;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manifest endpoint at {@code /agents/inventory/manifest} — the orchestrator scrapes this on startup
 * to build its intent-classifier few-shot (manifest-driven routing).
 */
@RestController
public class ManifestController {

    private final AgentManifest manifest;

    public ManifestController(AgentManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping("/agents/inventory/manifest")
    public AgentManifest manifest() {
        return manifest;
    }
}
