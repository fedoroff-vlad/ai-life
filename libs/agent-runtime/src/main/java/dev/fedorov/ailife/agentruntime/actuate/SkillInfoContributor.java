package dev.fedorov.ailife.agentruntime.actuate;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surfaces the loaded skill inventory under {@code /actuator/info} as
 * {@code skills.{count, names, triggers}} so a deploy smoke check can verify the
 * registry from outside the JVM — without reading startup logs.
 *
 * <p>This is the quiet observability lane that complements PR32's loud-at-startup
 * fail-fast: PR32 aborts boot when a declared skill fails to load; this lets ops
 * confirm the live registry after a green start. Registered by
 * {@code AgentRuntimeConfig}; appears only when the {@code info} endpoint is
 * exposed (both agents include it).
 */
public class SkillInfoContributor implements InfoContributor {

    private final SkillRegistry registry;

    public SkillInfoContributor(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void contribute(Info.Builder builder) {
        List<Skill> all = registry.all();
        Map<String, Object> skills = new LinkedHashMap<>();
        skills.put("count", all.size());
        skills.put("names", all.stream().map(Skill::name).sorted().toList());
        skills.put("triggers", all.stream()
                .flatMap(s -> (s.triggers() == null ? List.<String>of() : s.triggers()).stream())
                .distinct().sorted().toList());
        builder.withDetail("skills", skills);
    }
}
