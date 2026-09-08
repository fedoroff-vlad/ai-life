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

    private static Map<String, Object> baseProps(String mcpServerName) {
        Map<String, Object> p = new HashMap<>();
        // Only the three JPA modules read these; the capability MCPs ignore them.
        p.put("spring.datasource.url", jdbcUrl());
        p.put("spring.datasource.username", username());
        p.put("spring.datasource.password", password());
        p.put("spring.jpa.hibernate.ddl-auto", "none");
        p.put("spring.jpa.open-in-view", "false");
        p.put("spring.jpa.properties.hibernate.type.json_format_mapper",
                "dev.fedorov.ailife.common.jackson.Jackson3JsonFormatMapper");
        // each context gets its own MCP server identity + a random web port (proves distinct servers)
        p.put("spring.ai.mcp.server.name", mcpServerName);
        p.put("spring.ai.mcp.server.version", "0.0.1");
        p.put("spring.ai.mcp.server.type", "ASYNC");
        p.put("server.port", "0");
        return p;
    }

    @Test
    void residentHotDomainMcpContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (DomainMcpHost.Hosted hosted : DomainMcpHost.RESIDENT_HOT) {
            ConfigurableApplicationContext ctx =
                    DomainMcpHost.boot(hosted, baseProps("ai-life-" + hosted.configName() + "-hosted"));
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
                    .as("%s owns its application bean %s", DomainMcpHost.RESIDENT_HOT.get(i).configName(), own)
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
