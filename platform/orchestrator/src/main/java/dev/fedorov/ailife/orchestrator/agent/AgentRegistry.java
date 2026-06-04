package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.AgentManifest;

import java.util.Map;

/**
 * Read-only snapshot of remote agents discovered at startup. {@link #manifests()}
 * keys = agent names; values = the parsed AGENT.md from each agent's
 * {@code GET /agents/<name>/manifest}. Agents whose manifest fetch failed at
 * startup are absent here and won't be candidates for intent routing.
 */
public class AgentRegistry {

    private final Map<String, AgentManifest> manifests;

    public AgentRegistry(Map<String, AgentManifest> manifests) {
        this.manifests = Map.copyOf(manifests);
    }

    public Map<String, AgentManifest> manifests() {
        return manifests;
    }
}
