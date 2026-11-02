package dev.fedorov.ailife.deploy.agenthost;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3q — the packaging half of the per-module config location.
 *
 * <p>The host boots each co-hosted agent with {@code spring.config.location} pointing at
 * {@code classpath:/config/<name>/}, which only exists because every agent's pom lands a copy of its
 * own {@code application.yml} there. That coupling lives in six separate poms and nothing else reads
 * it. For an agent the stakes are higher than a missing port: a context that fell back to a sibling's
 * configuration would boot <b>someone else's persona and skills</b>.
 *
 * <p>No Spring context and no containers: a fast unit test on the packaged classpath.
 */
class AgentHostConfigLocationTest {

    @Test
    void everyCoHostedAgentShipsItsOwnYmlAtThePerModuleLocation() throws IOException {
        assertThat(AgentHost.RESIDENT_HOT).hasSize(6);

        for (AgentHost.Agent agent : AgentHost.RESIDENT_HOT) {
            String path = "config/" + agent.name() + "/application.yml";
            Resource resource = new ClassPathResource(path);

            assertThat(resource.exists())
                    .as("%s must land its application.yml at %s (pom <targetPath>) — the host boots it "
                            + "with spring.config.location=%s", agent.name(), path, agent.configLocation())
                    .isTrue();

            String yml = resource.getContentAsString(StandardCharsets.UTF_8);

            // It is that agent's own file, not a sibling's — for an agent this is its persona.
            assertThat(yml)
                    .as("%s's per-module yml must be its own", agent.name())
                    .contains("name: " + agent.name());

            // The three values the launcher used to re-supply in Java now live only here.
            assertThat(yml)
                    .as("%s's yml must declare its own manifest path", agent.name())
                    .contains("manifest/" + agent.name() + "/AGENT.md");
            assertThat(yml)
                    .as("%s's yml must declare its reactive web stack", agent.name())
                    .contains("web-application-type: reactive");
        }
    }

    @Test
    void theDefaultLocationIsKeptSoStandaloneJarsAreUnaffected() throws IOException {
        // Each agent also keeps application.yml at the default classpath root; the standalone
        // -exec.jar still resolves it, so the co-hosting change is invisible to the separate processes.
        Resource[] defaults = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/application.yml");

        assertThat(defaults.length)
                .as("all six co-hosted agents keep application.yml at the classpath root")
                .isGreaterThanOrEqualTo(AgentHost.RESIDENT_HOT.size());
    }

    @Test
    void theConfigLocationIsNotOptionalSoAMissingCopyFailsFast() {
        for (AgentHost.Agent agent : AgentHost.RESIDENT_HOT) {
            assertThat(agent.configLocation())
                    .as("a missing per-module yml must fail the context, not boot a sibling's persona")
                    .doesNotStartWith("optional:")
                    .isEqualTo("classpath:/config/" + agent.name() + "/");
        }
    }
}
