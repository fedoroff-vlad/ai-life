package dev.fedorov.ailife.agents.calendar.skill;

import java.util.List;

/**
 * Parsed SKILL.md. {@link #body()} is the markdown body — the skill's domain
 * prompt, layered on top of the agent's AGENT.md prompt when the skill runs.
 * Skills don't own state and aren't agents — they're prompt + I/O contract.
 */
public record Skill(
        String name,
        String description,
        String version,
        String domain,
        List<String> triggers,
        List<String> languages,
        String body) {
}
