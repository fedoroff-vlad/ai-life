package dev.fedorov.ailife.agents.calendar.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Read-only index of loaded skills. Built once at startup by {@link SkillLoader}.
 * Lookup happens by trigger {@code kind} — the same string the scheduler emits
 * in {@code AgentWakeRequest.kind}.
 */
public class SkillRegistry {

    private final List<Skill> all;
    private final Map<String, Skill> byTrigger;

    public SkillRegistry(List<Skill> skills) {
        this.all = List.copyOf(skills);
        this.byTrigger = skills.stream()
                .flatMap(s -> (s.triggers() == null ? List.<String>of() : s.triggers()).stream()
                        .map(t -> Map.entry(t, s)))
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a /* first-wins; one skill per trigger by design */));
    }

    public List<Skill> all() { return all; }

    public Optional<Skill> forTrigger(String kind) {
        return Optional.ofNullable(byTrigger.get(kind));
    }
}
