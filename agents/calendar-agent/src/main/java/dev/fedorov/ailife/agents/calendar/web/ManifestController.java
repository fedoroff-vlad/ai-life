package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manifest endpoint at {@code /agents/calendar/manifest} — orchestrator scrapes
 * this on startup to build its intent-classifier few-shot. Path matches the
 * {@code /agents/<name>/...} pattern used by intent/triggers/skills.
 */
@RestController
public class ManifestController {

    private final AgentManifest manifest;

    public ManifestController(AgentManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping("/agents/calendar/manifest")
    public AgentManifest manifest() {
        return manifest;
    }
}
