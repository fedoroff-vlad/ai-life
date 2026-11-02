package dev.fedorov.ailife.deploy.mcphost;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3n — the packaging half of the per-module config location.
 *
 * <p>The host boots each co-hosted context with {@code spring.config.location} pointing at
 * {@code classpath:/config/<module>/}, which only exists because every co-hosted module's pom lands a
 * copy of its own {@code application.yml} there. That coupling lives in five separate poms and nothing
 * else reads it, so without this test a dropped resource block would surface only at deploy — as a
 * context failing to start, or worse, silently starting on another module's configuration.
 *
 * <p>No Spring context and no containers: this is a fast unit test on the packaged classpath.
 */
class DomainMcpHostConfigLocationTest {

    /** The production port each module's own yml must declare — consolidation re-wires no ports. */
    private static final Map<String, String> EXPECTED_PORT = Map.of(
            "mcp-caldav", "8090",
            "mcp-finance", "8092",
            "mcp-tasks", "8095",
            "mcp-web", "8098",
            "mcp-media-processing", "8097");

    @Test
    void everyCoHostedModuleShipsItsOwnYmlAtThePerModuleLocation() throws IOException {
        assertThat(DomainMcpHost.RESIDENT_HOT).hasSize(5);

        for (DomainMcpHost.Hosted hosted : DomainMcpHost.RESIDENT_HOT) {
            String path = "config/" + hosted.module() + "/application.yml";
            Resource resource = new ClassPathResource(path);

            assertThat(resource.exists())
                    .as("%s must land its application.yml at %s (pom <targetPath>) — the host boots it "
                            + "with spring.config.location=%s", hosted.module(), path, hosted.configLocation())
                    .isTrue();

            String yml = resource.getContentAsString(StandardCharsets.UTF_8);

            // It is that module's own file, not a sibling's — the whole point of the per-module path.
            assertThat(yml)
                    .as("%s's per-module yml must be its own", hosted.module())
                    .contains("name: " + hosted.module());

            // The production port survives consolidation, so agents' /internal/* URLs stay valid.
            assertThat(yml)
                    .as("%s's yml must declare its production port", hosted.module())
                    .contains(":" + EXPECTED_PORT.get(hosted.module()) + "}");
        }
    }

    @Test
    void theDefaultLocationIsKeptSoStandaloneJarsAreUnaffected() throws IOException {
        // Each module also keeps application.yml at the default classpath root; the standalone
        // -exec.jar still resolves it, so the co-hosting change is invisible to the separate processes.
        Resource[] defaults = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/application.yml");

        assertThat(defaults.length)
                .as("all five co-hosted modules keep application.yml at the classpath root")
                .isGreaterThanOrEqualTo(DomainMcpHost.RESIDENT_HOT.size());
    }

    @Test
    void theConfigLocationIsNotOptionalSoAMissingCopyFailsFast() {
        for (DomainMcpHost.Hosted hosted : DomainMcpHost.RESIDENT_HOT) {
            assertThat(hosted.configLocation())
                    .as("a missing per-module yml must fail the context, not fall back to a sibling's")
                    .doesNotStartWith("optional:")
                    .isEqualTo("classpath:/config/" + hosted.module() + "/");
        }
    }
}
