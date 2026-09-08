package dev.fedorov.ailife.deploy.mcphost;

import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3b — proves the Path B / B1 mechanism: two real domain-MCP module contexts
 * boot <b>side-by-side in one JVM</b>, each on its own port with its own MCP server, with zero change
 * to the modules. This is the consolidation building block; the RAM delta (one host vs N separate
 * JVMs) is measured separately by {@code scripts/measure-footprint.sh} at deploy.
 *
 * <p>Contexts boot against the shared Testcontainers PG with {@code ddl-auto=none} (the modules apply
 * no Liquibase at boot, so no schema is required to start the web + MCP servers — enough to prove
 * co-residency).
 */
class DomainMcpHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    private static Map<String, Object> baseProps(String mcpServerName) {
        Map<String, Object> p = new HashMap<>();
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
    void twoDomainMcpContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        // briefing context (carries its custom mcp-briefing.* props)
        Map<String, Object> briefing = baseProps("ai-life-briefing-hosted");
        briefing.put("mcp-briefing.scheduler-url", "http://localhost:1");
        ConfigurableApplicationContext c1 = DomainMcpHost.boot(DomainMcpHost.PILOT.get(0), briefing);
        CONTEXTS.add(c1);

        // travel context
        ConfigurableApplicationContext c2 =
                DomainMcpHost.boot(DomainMcpHost.PILOT.get(1), baseProps("ai-life-travel-hosted"));
        CONTEXTS.add(c2);

        // both are live, independent contexts...
        assertThat(c1.isRunning()).isTrue();
        assertThat(c2.isRunning()).isTrue();
        assertThat(c1).isNotSameAs(c2);
        assertThat(ProcessHandle.current().pid())
                .as("both contexts run in this single JVM process")
                .isEqualTo(pid);

        // ...on distinct web ports (each MCP kept its own server, no port collision)
        int p1 = c1.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        int p2 = c2.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        assertThat(p1).isPositive();
        assertThat(p2).isPositive();
        assertThat(p1).as("co-hosted MCPs bind distinct ports").isNotEqualTo(p2);

        // ...each loaded its OWN module's beans (config-name skip did not cross-wire them)
        assertThat(c1.containsBean("mcpBriefingApplication")).isTrue();
        assertThat(c2.containsBean("mcpTravelApplication")).isTrue();
        assertThat(c1.containsBean("mcpTravelApplication")).isFalse();
    }
}
