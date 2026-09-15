package dev.fedorov.ailife.deploy.financeauxhost;

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
 * ADR-0006 / #584 slice 3j — proves the Path B / B1 mechanism on the <b>fifth cold host-unit</b>, the
 * <b>Finance-aux</b> unit: the two aux capability-MCPs ({@code mcp-market-data}, {@code mcp-chart-render})
 * and the two import domain-MCPs ({@code mcp-money-pro-import}, {@code mcp-ics-import}) boot
 * <b>side-by-side in one JVM</b>, each on its own port, with zero change to the modules. This is the first
 * <b>MCP-only</b> cold unit (no agent), so — like the resident Domain-MCP-hot host (3c) — it needs neither
 * the per-agent manifest-path fix nor the {@code agent.skills-classpath} re-supply, only the shared
 * {@code application.yml} skip and the mixed reactive/servlet web-type pin. The RAM delta (one host vs four
 * separate JVMs) is measured separately by {@code scripts/measure-footprint.sh} at deploy (Mac).
 *
 * <p>{@code mcp-money-pro-import} and {@code mcp-ics-import} are DB-bound, so they are wired to the shared
 * Testcontainers PG with {@code ddl-auto=none} (they apply no Liquibase at boot and hold no fatal startup
 * query, so no schema is needed to start the web + MCP servers). The two capability-MCPs are schema-less;
 * the datasource is harmless for them. No {@code AGENT.md} is on this host's classpath (there is no agent),
 * so there is no manifest concern at all.
 */
class FinanceAuxHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each module's default Spring bean name (decapitalised {@code @SpringBootApplication} class),
        in the same order as {@link FinanceAuxHost#COLD_FINANCE_AUX}. */
    private static final List<String> APP_BEANS = List.of(
            "mcpMarketDataApplication",
            "mcpChartRenderApplication",
            "mcpMoneyProImportApplication",
            "mcpIcsImportApplication");

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /** Base props common to every context (datasource is harmless for the schema-less capability-MCPs). */
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

    /** Per-module boot needs, keyed by the launcher's short name. Every module is an MCP server, so each
        re-supplies its MCP-server identity (the config-name skip dropped the module's own). */
    private static Map<String, Object> extraFor(FinanceAuxHost.Hosted hosted) {
        Map<String, Object> p = baseProps();
        p.put("spring.ai.mcp.server.name", "ai-life-" + hosted.name() + "-hosted");
        p.put("spring.ai.mcp.server.version", "0.0.1");
        p.put("spring.ai.mcp.server.type", "ASYNC");
        return p;
    }

    @Test
    void financeAuxColdHostContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (FinanceAuxHost.Hosted hosted : FinanceAuxHost.COLD_FINANCE_AUX) {
            ConfigurableApplicationContext ctx = FinanceAuxHost.boot(hosted, extraFor(hosted));
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(4);

        // all are live, independent contexts, all in THIS single JVM process — four MCPs, no agent
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("the four aux MCPs run in this single JVM process")
                .isEqualTo(pid);

        // ...on four distinct web ports (each kept its own server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("the co-hosted four MCPs bind distinct ports").hasSize(4);

        // ...each context loaded ONLY its own module's application bean (config-name skip did not cross-wire)
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String own = APP_BEANS.get(i);
            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", FinanceAuxHost.COLD_FINANCE_AUX.get(i).name(), own)
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
