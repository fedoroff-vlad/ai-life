package dev.fedorov.ailife.deploy.platformhost;

import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3e (co-residency) + 3s (per-module config) — proves the Path B / B1 mechanism on
 * the real <b>resident Platform-hot</b> set: the seven always-on platform service contexts
 * (gateway-telegram · orchestrator · notifier · profile · conversation · media · scheduler) boot
 * <b>side-by-side in one JVM</b>, each on its own port, with zero change to the modules. The RAM delta
 * (one host vs seven separate JVMs) is measured separately by {@code scripts/measure-footprint.sh} at
 * deploy (Mac).
 *
 * <p><b>Each context now reads its own {@code application.yml}</b> (slice 3s), via
 * {@code spring.config.location=classpath:/config/<name>/}. So this test only overrides what a test must:
 * the datasource (Testcontainers), a random port, and three module-specific boot needs. Everything else —
 * ports, outbound URLs, toggles — comes from the same files the deployed process will read, which is the
 * point: the IT and the deploy now share one wiring path.
 *
 * <p><b>The blind spot this slice closed.</b> Until 3s this test handed {@code baseProps()} — a live
 * datasource included — to <b>all seven</b> contexts, {@code orchestrator} among them, although
 * orchestrator owns no schema and its yml declares no datasource. That is the same masking that let the
 * Domain-MCP-hot host ship a broken deploy behind a green IT (slice 3o): the test supplied what the
 * merged classpath would otherwise demand, so it could never see the demand. Now only schema-bound
 * members get a datasource, and orchestrator is asserted to hold no {@code DataSource} bean at all —
 * which fails unless {@link PlatformHost.Hosted#excludedAutoConfigurations()} keeps JPA/DataSource
 * auto-configuration off for it.
 *
 * <p>Two module-specific boot needs are handled without changing any module:
 * <ul>
 *   <li><b>media-service</b> ensures its bucket at boot ({@code @PostConstruct}), so a live S3 store is
 *       provided by a SeaweedFS container — the same one the media-service IT uses;</li>
 *   <li>the two {@code @Scheduled} ticks are kept quiet during the short test:
 *       {@code notifier.held-redrain-enabled=false} (the notifier tests' own toggle) and a far-future
 *       {@code scheduler.tick-millis} so the scheduler tick never fires against the (absent) schema.</li>
 * </ul>
 * gateway-telegram boots with the empty bot token its own yml defaults to, so the Telegram long-poll and
 * the inbox redriver stay off — the rest of the service still starts, exactly as in CI/IDE runs.
 */
class PlatformHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int S3_PORT = 8333;

    /** The object store media-service talks to — SeaweedFS (S3 API); see media-service/README. */
    static final GenericContainer<?> OBJECT_STORE;

    static {
        OBJECT_STORE = new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:3.97"))
                .withCommand("server", "-dir=/data", "-s3", "-s3.port=" + S3_PORT, "-ip.bind=0.0.0.0")
                .withExposedPorts(S3_PORT)
                .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*", 1));
        OBJECT_STORE.start();
    }

    private static String s3Endpoint() {
        return "http://" + OBJECT_STORE.getHost() + ":" + OBJECT_STORE.getMappedPort(S3_PORT);
    }

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each hot service's default Spring bean name (decapitalised {@code @SpringBootApplication} class). */
    private static final List<String> APP_BEANS = List.of(
            "gatewayApplication",
            "orchestratorApplication",
            "notifierApplication",
            "profileServiceApplication",
            "conversationServiceApplication",
            "mediaServiceApplication",
            "schedulerApplication");

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /** The datasource + JPA props a <b>schema-bound</b> context needs; never handed to a schema-less one. */
    private static Map<String, Object> datasourceProps() {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", jdbcUrl());
        p.put("spring.datasource.username", username());
        p.put("spring.datasource.password", password());
        p.put("spring.jpa.hibernate.ddl-auto", "none");
        p.put("spring.jpa.open-in-view", "false");
        p.put("spring.jpa.properties.hibernate.type.json_format_mapper",
                "dev.fedorov.ailife.common.jackson.Jackson3JsonFormatMapper");
        return p;
    }

    /** Per-member overrides: a test port, a datasource only where the module owns a schema, boot needs. */
    private static Map<String, Object> overridesFor(PlatformHost.Hosted hosted) {
        Map<String, Object> p = new HashMap<>();
        p.put("server.port", "0"); // random port → proves distinct servers

        // Only schema-bound members get a datasource. Handing one to orchestrator too is exactly what
        // hid the Domain-MCP-hot host's deploy failure behind a green IT (slice 3o).
        if (hosted.persistence() == PlatformHost.Persistence.SCHEMA_BOUND) {
            p.putAll(datasourceProps());
        }

        switch (hosted.name()) {
            case "media-service" -> {
                // No -s3.config identity file → any credentials are accepted.
                p.put("media.s3.endpoint", s3Endpoint());
                p.put("media.s3.access-key", "test-access");
                p.put("media.s3.secret-key", "test-secret");
            }
            // keep the @Scheduled ticks quiet for the duration of the test
            case "notifier-service" -> p.put("notifier.held-redrain-enabled", "false");
            case "scheduler-service" -> p.put("scheduler.tick-millis", "3600000");
            default -> { /* no extra boot need */ }
        }
        return p;
    }

    private static int statusOf(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            try {
                return connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new IllegalStateException("probing " + url + " failed", e);
        }
    }

    @Test
    void residentHotPlatformContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (PlatformHost.Hosted hosted : PlatformHost.RESIDENT_HOT) {
            ConfigurableApplicationContext ctx = PlatformHost.boot(hosted, overridesFor(hosted));
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(7);

        // all seven are live, independent contexts, all in THIS single JVM process
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("all co-hosted platform contexts run in this single JVM process")
                .isEqualTo(pid);

        // ...on seven distinct web ports (each service kept its own server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("each co-hosted platform service binds a distinct port").hasSize(7);

        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            PlatformHost.Hosted hosted = PlatformHost.RESIDENT_HOT.get(i);
            String own = APP_BEANS.get(i);

            // ...each context loaded ONLY its own module's application bean (no cross-wiring)
            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", hosted.name(), own)
                    .isTrue();
            for (String other : APP_BEANS) {
                if (!other.equals(own)) {
                    assertThat(ctx.containsBean(other))
                            .as("%s must not carry sibling bean %s", own, other)
                            .isFalse();
                }
            }

            // ...reading its OWN application.yml (slice 3s), not a sibling's and not none at all
            assertThat(ctx.getEnvironment().getProperty("spring.application.name"))
                    .as("%s must boot on its own per-module yml", hosted.name())
                    .isEqualTo(hosted.name());

            // ...on the web stack the launcher declared for it. On a merged classpath Boot would deduce
            // SERVLET for all seven (four members bring spring-boot-starter-web), which on the
            // Domain-MCP-hot host meant the reactive members mounted their routes nowhere while health
            // stayed green (slice 3p). Assert the stack itself, not just that something answers.
            if ("reactive".equals(hosted.webType())) {
                assertThat(ctx)
                        .as("%s must run on the reactive stack its launcher entry declares", hosted.name())
                        .isInstanceOf(ReactiveWebApplicationContext.class);
            } else {
                assertThat(ctx)
                        .as("%s must run on the servlet stack its launcher entry declares", hosted.name())
                        .isInstanceOf(WebApplicationContext.class);
            }

            // ...and its server actually serves on that port
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(statusOf("http://127.0.0.1:" + port + "/actuator/health"))
                    .as("%s must answer on its own port", hosted.name())
                    .isEqualTo(200);

            // A schema-less member must hold no DataSource at all. Co-hosted it inherits its siblings'
            // JPA starters, so without the launcher's excludes Boot would auto-configure one it has no
            // URL for and the context would die — the slice 3o failure, now visible here.
            if (hosted.persistence() == PlatformHost.Persistence.SCHEMA_LESS) {
                assertThat(ctx.getBeanNamesForType(DataSource.class))
                        .as("%s owns no schema, so no DataSource may be auto-configured for it "
                                + "from its siblings' starters", hosted.name())
                        .isEmpty();
            }
        }
    }
}
