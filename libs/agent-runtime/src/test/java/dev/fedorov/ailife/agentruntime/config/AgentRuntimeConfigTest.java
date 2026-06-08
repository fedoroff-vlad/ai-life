package dev.fedorov.ailife.agentruntime.config;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the loader hardening introduced by PR32. The {@code @Bean}
 * method is invoked directly (no Spring context) — we exercise the cross-check
 * between AGENT.md's {@code skills:} list and the SKILL.md files actually found
 * on the classpath.
 *
 * <p>Test resources live under {@code src/test/resources/test-skills/}: a
 * {@code good/SKILL.md} that parses cleanly and a {@code bad/SKILL.md} whose
 * frontmatter trips the SnakeYAML colon-space gotcha that PR31 ran into.
 */
class AgentRuntimeConfigTest {

    private static final String TEST_SKILLS_PATTERN = "classpath*:test-skills/*/SKILL.md";

    private final AgentRuntimeConfig config = new AgentRuntimeConfig();

    @Test
    void happyPathLoadsDeclaredSkills() {
        AgentRuntimeProperties props = props(TEST_SKILLS_PATTERN);
        AgentManifest manifest = manifest(List.of("good-skill"));

        SkillRegistry registry = config.skillRegistry(props, manifest);

        // Both files are scanned but only the good one parses; the missing
        // 'bad-skill' is NOT declared, so the hardening lets us through.
        assertThat(registry.all()).extracting(Skill::name).contains("good-skill");
        assertThat(registry.forTrigger("test.good")).isPresent();
        assertThat(registry.forTrigger("test.bad")).isEmpty();
    }

    @Test
    void declaringTheBrokenSkillFailsStartupWithDetails() {
        AgentRuntimeProperties props = props(TEST_SKILLS_PATTERN);
        // AGENT.md declares both — bad-skill is expected but won't load.
        AgentManifest manifest = manifest(List.of("good-skill", "bad-skill"));

        assertThatThrownBy(() -> config.skillRegistry(props, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[bad-skill]")
                .hasMessageContaining("Parse failures during scan")
                // Resource description from PathMatchingResourcePatternResolver
                // includes the path with the platform separator (forward on
                // *nix, backslash on Windows); assert path parts separately so
                // CI works on both.
                .hasMessageContaining("bad")
                .hasMessageContaining("SKILL.md")
                // Hints we want the next maintainer to act on.
                .hasMessageContaining("Fix the SKILL.md");
    }

    @Test
    void emptyDeclaredSkillsListOptsOutOfHardening() {
        AgentRuntimeProperties props = props(TEST_SKILLS_PATTERN);
        // Empty list = AGENT.md is intentionally not cross-checking.
        AgentManifest manifest = manifest(List.of());

        // Must NOT throw — even though bad-skill failed to parse, nothing was
        // declared so the cross-check is skipped. WARN-and-continue stays.
        SkillRegistry registry = config.skillRegistry(props, manifest);
        assertThat(registry.all()).extracting(Skill::name).containsExactly("good-skill");
    }

    @Test
    void declaringASkillThatDoesNotExistOnClasspathAlsoFails() {
        // Point at the good/ subtree only — keeps parse-failures empty so we
        // can also assert the "no parse failures" hint branch in the message.
        AgentRuntimeProperties props = props("classpath*:test-skills/good/SKILL.md");
        AgentManifest manifest = manifest(List.of("good-skill", "ghost-skill"));

        assertThatThrownBy(() -> config.skillRegistry(props, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[ghost-skill]")
                .hasMessageContaining("name mismatch or skills-classpath pattern that did not match");
    }

    @Test
    void blankSkillsClasspathReturnsEmptyRegistry() {
        AgentRuntimeProperties props = props("");
        AgentManifest manifest = manifest(List.of());

        SkillRegistry registry = config.skillRegistry(props, manifest);
        assertThat(registry.all()).isEmpty();
    }

    private static AgentRuntimeProperties props(String pattern) {
        AgentRuntimeProperties p = new AgentRuntimeProperties();
        p.setSkillsClasspath(pattern);
        return p;
    }

    private static AgentManifest manifest(List<String> skills) {
        return new AgentManifest(
                "test-agent",
                "for tests",
                "0.0.1",
                0,
                List.<String>of(),
                skills,
                List.<Map<String, String>>of(),
                List.<Map<String, String>>of(),
                "body");
    }
}
