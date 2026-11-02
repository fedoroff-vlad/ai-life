package dev.fedorov.ailife.deploy.mcphost;

import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3c — proves the Path B / B1 mechanism on the real <b>resident Domain-MCP-hot</b>
 * set: the five always-on MCP module contexts (caldav · finance · tasks · web · media-processing) boot
 * <b>side-by-side in one JVM</b>, each on its own port with its own MCP server, with zero change to the
 * modules. This is the consolidation building block; the RAM delta (one host vs five separate JVMs) is
 * measured separately by {@code scripts/measure-footprint.sh} at deploy (Mac).
 *
 * <p>Contexts boot against the shared Testcontainers PG with {@code ddl-auto=none} (the modules apply no
 * Liquibase at boot and hold no fatal startup bean, so no schema is required to start the web + MCP
 * servers — enough to prove co-residency). The two schema-less capability MCPs (web, media-processing)
 * ignore the datasource props.
 *
 * <p><b>Slice 3n:</b> each context now reads <b>its own</b> {@code application.yml} from the per-module
 * classpath location instead of being booted with its configuration skipped, so this IT also guards what
 * that buys — the module's real identity and its {@code spring.ai.mcp.server.instructions}, the
 * tool-surface prose the old {@code spring.config.name} skip silently dropped. Overrides go through
 * {@link DomainMcpHost#boot} as command-line args so they outrank the yml.
 */
class DomainMcpHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each hot module's default Spring bean name (decapitalised {@code @SpringBootApplication} class). */
    private static final List<String> APP_BEANS = List.of(
            "mcpCaldavApplication",
            "mcpFinanceApplication",
            "mcpTasksApplication",
            "mcpWebApplication",
            "mcpMediaProcessingApplication");

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /**
     * What the test must override on top of each module's own yml. Deliberately minimal: the MCP server
     * identity, the port default and every module-specific setting now come from the module's own
     * configuration, which is the point of slice 3n.
     */
    private static Map<String, Object> overrides() {
        Map<String, Object> p = new HashMap<>();
        // Only the three JPA modules read these; the capability MCPs ignore them.
        p.put("spring.datasource.url", jdbcUrl());
        p.put("spring.datasource.username", username());
        p.put("spring.datasource.password", password());
        p.put("spring.jpa.hibernate.ddl-auto", "none");
        p.put("spring.jpa.open-in-view", "false");
        p.put("spring.jpa.properties.hibernate.type.json_format_mapper",
                "dev.fedorov.ailife.common.jackson.Jackson3JsonFormatMapper");
        // Ephemeral ports: the yml declares the production ones (8090 · 8092 · 8095 · 8098 · 8097) and
        // DomainMcpHostConfigLocationTest asserts that; binding them here would collide with a locally
        // running stack and with a sibling host IT under CI's -T2.
        p.put("server.port", "0");
        return p;
    }

    @Test
    void residentHotDomainMcpContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (DomainMcpHost.Hosted hosted : DomainMcpHost.RESIDENT_HOT) {
            ConfigurableApplicationContext ctx = DomainMcpHost.boot(hosted, overrides());
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(5);

        // all five are live, independent contexts, all in THIS single JVM process
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("all co-hosted contexts run in this single JVM process")
                .isEqualTo(pid);

        // ...on five distinct web ports (each MCP kept its own server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("each co-hosted MCP binds a distinct port").hasSize(5);

        // ...each context loaded ONLY its own module's application bean (config-name skip did not cross-wire)
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String own = APP_BEANS.get(i);
            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", DomainMcpHost.RESIDENT_HOT.get(i).module(), own)
                    .isTrue();
            for (String other : APP_BEANS) {
                if (!other.equals(own)) {
                    assertThat(ctx.containsBean(other))
                            .as("%s must not carry sibling bean %s", own, other)
                            .isFalse();
                }
            }
        }

        // ...and each read ITS OWN application.yml from the per-module config location (slice 3n).
        // Under the old spring.config.name skip both of these were absent: the module had no identity
        // of its own and the MCP instructions — the tool-surface prose clients are handed — were lost.
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String module = DomainMcpHost.RESIDENT_HOT.get(i).module();

            assertThat(ctx.getEnvironment().getProperty("spring.application.name"))
                    .as("%s must boot on its own application.yml, not a sibling's", module)
                    .isEqualTo(module);

            assertThat(ctx.getEnvironment().getProperty("spring.ai.mcp.server.instructions"))
                    .as("%s's MCP server instructions must survive co-hosting", module)
                    .isNotBlank();
        }
    }
}
