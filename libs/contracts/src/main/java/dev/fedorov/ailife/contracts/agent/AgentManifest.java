package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Wire shape for {@code GET /agents/<name>/manifest}. Mirrors the YAML frontmatter
 * of {@code agents/<name>/AGENT.md} plus the markdown body (= the agent's system
 * prompt). The orchestrator scrapes this on startup to build its intent-classifier
 * few-shot — keep field names stable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentManifest(
        String name,
        String description,
        String version,
        Integer port,
        List<String> mcp,
        List<String> skills,
        List<Map<String, String>> triggers,
        List<Map<String, String>> intents,
        String body) {
}
