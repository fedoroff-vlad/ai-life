package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.agents.calendar.manifest.AgentManifest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManifestController {

    private final AgentManifest manifest;

    public ManifestController(AgentManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping("/manifest")
    public AgentManifest manifest() {
        return manifest;
    }
}
