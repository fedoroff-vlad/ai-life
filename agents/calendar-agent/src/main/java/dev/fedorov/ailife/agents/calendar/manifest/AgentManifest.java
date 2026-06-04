package dev.fedorov.ailife.agents.calendar.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing an {@code AGENT.md} file. Shape mirrors the YAML frontmatter;
 * {@link #body()} is the markdown body (the agent's system prompt). The orchestrator
 * consumes this via {@code GET /manifest} when building its intent-classifier
 * few-shot, so the field names here are part of the wire contract — keep them stable.
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
