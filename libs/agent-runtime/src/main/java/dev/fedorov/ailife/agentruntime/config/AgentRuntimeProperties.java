package dev.fedorov.ailife.agentruntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the runtime looks for the agent's manifest + skills on the classpath.
 *
 * <p>Defaults assume the canonical layout: {@code AGENT.md} at the module root
 * (copied onto the classpath by the agent's pom.xml) and {@code SKILL.md} files
 * under {@code skills/<domain>/<name>/} (also copied at build time). Each agent
 * is expected to override {@code skills-classpath} so domain A doesn't load
 * domain B's skills — there is no global default that does the right thing for
 * more than one agent.
 */
@ConfigurationProperties(prefix = "agent")
public class AgentRuntimeProperties {

    private String manifestClasspath = "AGENT.md";
    /**
     * Spring resource-pattern for {@code SKILL.md} files. An agent override
     * looks like {@code classpath*:skills/calendar/} *{@code /SKILL.md}.
     * The default scans nothing — keeps an unconfigured agent silent rather
     * than accidentally cross-loading another domain's skills.
     */
    private String skillsClasspath = "";
    /** Top-k requested from memory-service when enriching the skill prompt. */
    private int memoryRecallK = 5;

    public String getManifestClasspath() { return manifestClasspath; }
    public void setManifestClasspath(String manifestClasspath) {
        this.manifestClasspath = manifestClasspath;
    }

    public String getSkillsClasspath() { return skillsClasspath; }
    public void setSkillsClasspath(String skillsClasspath) {
        this.skillsClasspath = skillsClasspath;
    }

    public int getMemoryRecallK() { return memoryRecallK; }
    public void setMemoryRecallK(int memoryRecallK) { this.memoryRecallK = memoryRecallK; }
}
