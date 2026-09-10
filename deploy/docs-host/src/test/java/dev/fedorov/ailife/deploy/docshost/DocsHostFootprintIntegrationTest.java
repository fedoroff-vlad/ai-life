package dev.fedorov.ailife.deploy.docshost;

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
 * ADR-0006 / #584 slice 3f — proves the Path B / B1 mechanism on the <b>first cold host-unit</b>, the
 * <b>Docs</b> unit: the agent context ({@code docs-agent}) and its domain-MCP context ({@code mcp-docs})
 * boot <b>side-by-side in one JVM</b>, each on its own port, with zero change to the modules. This is the
 * new thing cold hosts do that the resident tier doesn't — <b>mix the agent tier and its MCP in one
 * process</b> (grouped by co-usage affinity, started/stopped as a unit). The RAM delta (one host vs two
 * separate JVMs) is measured separately by {@code scripts/measure-footprint.sh} at deploy (Mac).
 *
 * <p>{@code mcp-docs} is DB-bound, so it is wired to the shared Testcontainers PG with {@code ddl-auto=none}
 * (it applies no Liquibase at boot and holds no fatal startup query, so no schema is needed to start the
 * web + MCP servers). {@code docs-agent} owns no schema; its MCP client is disabled here
 * ({@code spring.ai.mcp.client.enabled=false}) so it boots without the (not-co-hosted)
 * {@code mcp-media-processing} SSE endpoint — the agent→MCP SSE binding is a deploy concern, not part of
 * this co-residency proof. Only {@code docs-agent} carries an {@code AGENT.md} (at {@code classpath:/}),
 * so no per-agent manifest-path fix is needed for a single-agent host.
 */
class DocsHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each module's default Spring bean name (decapitalised {@code @SpringBootApplication} class). */
    private static final List<String> APP_BEANS = List.of(
            "mcpDocsApplication",
            "docsAgentApplication");

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /** Base props common to both contexts (datasource is harmless for the schema-less docs-agent). */
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
    private static Map<String, Object> extraFor(DocsHost.Hosted hosted) {
        Map<String, Object> p = baseProps();
        switch (hosted.name()) {
            case "mcp-docs" -> {
                // config-name skip dropped the module's MCP-server config; re-supply an identity.
                p.put("spring.ai.mcp.server.name", "ai-life-mcp-docs-hosted");
                p.put("spring.ai.mcp.server.version", "0.0.1");
                p.put("spring.ai.mcp.server.type", "ASYNC");
            }
            // co-residency proof only: don't require the agent's SSE endpoints at boot
            case "docs-agent" -> p.put("spring.ai.mcp.client.enabled", "false");
            default -> { /* no extra boot need */ }
        }
        return p;
    }

    @Test
    void docsColdHostContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (DocsHost.Hosted hosted : DocsHost.COLD_DOCS) {
            ConfigurableApplicationContext ctx = DocsHost.boot(hosted, extraFor(hosted));
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(2);

        // both are live, independent contexts, all in THIS single JVM process — an agent and its MCP
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("the agent and its MCP run in this single JVM process")
                .isEqualTo(pid);

        // ...on two distinct web ports (agent + MCP each kept its own server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("the co-hosted agent + MCP bind distinct ports").hasSize(2);

        // ...each context loaded ONLY its own module's application bean (config-name skip did not cross-wire)
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String own = APP_BEANS.get(i);
            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", DocsHost.COLD_DOCS.get(i).name(), own)
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
