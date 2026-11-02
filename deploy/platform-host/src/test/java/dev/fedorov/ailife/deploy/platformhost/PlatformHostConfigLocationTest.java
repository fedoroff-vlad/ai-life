package dev.fedorov.ailife.deploy.platformhost;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3s — the packaging half of the per-module config location, plus the two facts
 * the launcher asserts <i>about</i> the modules.
 *
 * <p>The host boots each co-hosted service with {@code spring.config.location} pointing at
 * {@code classpath:/config/<name>/}, which only exists because every service's pom lands a copy of its
 * own {@code application.yml} there. That coupling lives in seven separate poms and nothing else reads
 * it; a context that fell back to a sibling's configuration would bind someone else's port and dial
 * someone else's dependencies.
 *
 * <p>Two further checks keep {@link PlatformHost#RESIDENT_HOT} honest about the modules it hosts, because
 * both of its per-member flags are claims about files that live elsewhere:
 * <ul>
 *   <li>{@code Persistence} is derived from the yml, not trusted: a member is schema-bound exactly when
 *       its own yml carries a {@code spring.datasource}. Add one to {@code orchestrator} (or drop one
 *       anywhere else) and this fails until the list follows.</li>
 *   <li>{@code webType} is the launcher's only source for the five services whose yml is silent, but
 *       {@code gateway-telegram} and {@code scheduler-service} do declare their stack — and the launcher
 *       passes its value as a command-line argument, which <b>outranks</b> the yml. So where the yml
 *       speaks, the two must agree, or the host would quietly run a service on a stack its own
 *       configuration contradicts.</li>
 * </ul>
 *
 * <p>No Spring context and no containers: a fast unit test on the packaged classpath.
 */
class PlatformHostConfigLocationTest {

    /** Each service's production port, as its own {@code application.yml} must declare it. */
    private static final Map<String, Integer> PRODUCTION_PORT = Map.of(
            "gateway-telegram", 8080,
            "orchestrator", 8083,
            "profile-service", 8082,
            "notifier-service", 8084,
            "scheduler-service", 8085,
            "conversation-service", 8089,
            "media-service", 8088);

    /** Flatten a member's per-module yml copy into properties, placeholders left unresolved. */
    private static Map<String, Object> perModuleProperties(PlatformHost.Hosted hosted) throws IOException {
        Resource resource = new ClassPathResource("config/" + hosted.name() + "/application.yml");
        assertThat(resource.exists())
                .as("%s must land its application.yml at config/%s/ (pom <targetPath>) — the host boots "
                        + "it with spring.config.location=%s",
                        hosted.name(), hosted.name(), hosted.configLocation())
                .isTrue();

        Map<String, Object> flat = new LinkedHashMap<>();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(hosted.name(), resource);
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    flat.put(name, enumerable.getProperty(name));
                }
            }
        }
        return flat;
    }

    @Test
    void everyCoHostedServiceShipsItsOwnYmlAtThePerModuleLocation() throws IOException {
        assertThat(PlatformHost.RESIDENT_HOT).hasSize(7);

        for (PlatformHost.Hosted hosted : PlatformHost.RESIDENT_HOT) {
            Map<String, Object> props = perModuleProperties(hosted);

            // It is that service's own file, not a sibling's.
            assertThat(props.get("spring.application.name"))
                    .as("%s's per-module yml must be its own", hosted.name())
                    .isEqualTo(hosted.name());

            // The yml — not the launcher — carries the port, so the deploy env vars keep working.
            assertThat(String.valueOf(props.get("server.port")))
                    .as("%s's yml must declare its own production port", hosted.name())
                    .contains(String.valueOf(PRODUCTION_PORT.get(hosted.name())));
        }
    }

    @Test
    void thePersistenceFlagIsDerivedFromEachModulesOwnYml() throws IOException {
        for (PlatformHost.Hosted hosted : PlatformHost.RESIDENT_HOT) {
            boolean declaresDatasource = perModuleProperties(hosted).containsKey("spring.datasource.url");

            PlatformHost.Persistence expected = declaresDatasource
                    ? PlatformHost.Persistence.SCHEMA_BOUND
                    : PlatformHost.Persistence.SCHEMA_LESS;

            assertThat(hosted.persistence())
                    .as("%s's yml %s a spring.datasource, so RESIDENT_HOT must classify it %s — "
                            + "a schema-less member co-hosted WITHOUT the JPA excludes dies on "
                            + "\"Failed to determine a suitable driver class\" (slice 3o)",
                            hosted.name(),
                            declaresDatasource ? "declares" : "does not declare",
                            expected)
                    .isEqualTo(expected);
        }

        // Exactly one schema-less member today; if that changes, the excludes above must be revisited.
        assertThat(PlatformHost.RESIDENT_HOT.stream()
                .filter(h -> h.persistence() == PlatformHost.Persistence.SCHEMA_LESS)
                .map(PlatformHost.Hosted::name))
                .containsExactly("orchestrator");
    }

    @Test
    void theLauncherWebTypeMatchesEveryYmlThatDeclaresOne() throws IOException {
        int declared = 0;
        for (PlatformHost.Hosted hosted : PlatformHost.RESIDENT_HOT) {
            Object own = perModuleProperties(hosted).get("spring.main.web-application-type");
            if (own == null) {
                continue; // the launcher is this member's only source — see the class javadoc
            }
            declared++;
            assertThat(own)
                    .as("%s declares its own web stack, and the launcher's command-line argument "
                            + "outranks the yml — the two must not disagree", hosted.name())
                    .isEqualTo(hosted.webType());
        }
        assertThat(declared)
                .as("gateway-telegram and scheduler-service declare their web stack; if more modules "
                        + "start to, the launcher's re-supply can shrink the way slice 3q's did")
                .isEqualTo(2);
    }

    @Test
    void theDefaultLocationIsKeptSoStandaloneJarsAreUnaffected() throws IOException {
        // Each service also keeps application.yml at the default classpath root; the standalone
        // -exec.jar still resolves it, so the co-hosting change is invisible to the separate processes.
        Resource[] defaults = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/application.yml");

        assertThat(defaults.length)
                .as("all seven co-hosted services keep application.yml at the classpath root")
                .isGreaterThanOrEqualTo(PlatformHost.RESIDENT_HOT.size());
    }

    @Test
    void theConfigLocationIsNotOptionalSoAMissingCopyFailsFast() {
        for (PlatformHost.Hosted hosted : PlatformHost.RESIDENT_HOT) {
            assertThat(hosted.configLocation())
                    .as("a missing per-module yml must fail the context, not boot it on a sibling's "
                            + "port and outbound URLs")
                    .doesNotStartWith("optional:")
                    .isEqualTo("classpath:/config/" + hosted.name() + "/");
        }
    }
}
