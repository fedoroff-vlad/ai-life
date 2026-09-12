package dev.fedorov.ailife.deploy.platformhost;

import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3e — proves the Path B / B1 mechanism on the real <b>resident Platform-hot</b>
 * set: the seven always-on platform service contexts (gateway-telegram · orchestrator · notifier ·
 * profile · conversation · media · scheduler) boot <b>side-by-side in one JVM</b>, each on its own port,
 * with zero change to the modules. This is the consolidation building block; the RAM delta (one host vs
 * seven separate JVMs) is measured separately by {@code scripts/measure-footprint.sh} at deploy (Mac).
 *
 * <p>The platform tier is DB-bound, so every context is wired to the shared Testcontainers PG with
 * {@code ddl-auto=none} (the modules apply no Liquibase at boot and hold no fatal startup query, so no
 * schema is needed to start the web servers — enough to prove co-residency). Two module-specific boot
 * needs are handled without changing any module:
 * <ul>
 *   <li><b>media-service</b> ensures its bucket at boot ({@code @PostConstruct}), so a live MinIO is
 *       provided by a {@link MinIOContainer} — the same one the media-service IT uses;</li>
 *   <li>the two {@code @Scheduled} ticks are kept quiet during the short test:
 *       {@code notifier.held-redrain-enabled=false} (the notifier tests' own toggle) and a far-future
 *       {@code scheduler.tick-millis} so the scheduler tick never fires against the (absent) schema.</li>
 * </ul>
 * gateway-telegram boots with an empty bot token (its default) so the Telegram long-poll and the inbox
 * redriver stay off — the rest of the service still starts, exactly as in CI/IDE runs.
 */
class PlatformHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    static final MinIOContainer MINIO;

    static {
        // MinIO removed the minio/minio repo from Docker Hub (all tags 404); pull the image from MinIO's
        // canonical registry, quay.io. asCompatibleSubstituteFor keeps MinIOContainer happy with the
        // non-Docker-Hub registry path.
        MINIO = new MinIOContainer(
                DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                        .asCompatibleSubstituteFor("minio/minio"));
        MINIO.start();
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

    /** The datasource + JPA props every DB-bound context reads; harmless for the DB-less orchestrator. */
    private static Map<String, Object> baseProps() {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", jdbcUrl());
        p.put("spring.datasource.username", username());
        p.put("spring.datasource.password", password());
        p.put("spring.jpa.hibernate.ddl-auto", "none");
        p.put("spring.jpa.open-in-view", "false");
        p.put("spring.jpa.properties.hibernate.type.json_format_mapper",
                "dev.fedorov.ailife.common.jackson.Jackson3JsonFormatMapper");
        p.put("server.port", "0"); // random port → proves distinct servers
        return p;
    }

    /** Per-module boot needs, keyed by the launcher's short name. */
    private static Map<String, Object> extraFor(PlatformHost.Hosted hosted) {
        Map<String, Object> p = baseProps();
        switch (hosted.name()) {
            case "media-service" -> {
                p.put("media.minio.endpoint", MINIO.getS3URL());
                p.put("media.minio.access-key", MINIO.getUserName());
                p.put("media.minio.secret-key", MINIO.getPassword());
            }
            // keep the @Scheduled ticks quiet for the duration of the test
            case "notifier-service" -> p.put("notifier.held-redrain-enabled", "false");
            case "scheduler-service" -> p.put("scheduler.tick-millis", "3600000");
            default -> { /* no extra boot need */ }
        }
        return p;
    }

    @Test
    void residentHotPlatformContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (PlatformHost.Hosted hosted : PlatformHost.RESIDENT_HOT) {
            ConfigurableApplicationContext ctx = PlatformHost.boot(hosted, extraFor(hosted));
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

        // ...each context loaded ONLY its own module's application bean (config-name skip did not cross-wire)
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String own = APP_BEANS.get(i);
            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", PlatformHost.RESIDENT_HOT.get(i).name(), own)
                    .isTrue();
            for (String other : APP_BEANS) {
                if (!other.equals(own)) {
                    assertThat(ctx.containsBean(other))
                            .as("%s must not carry sibling bean %s", own, other)
                            .isFalse();
                }
            }
        }
    }
}
