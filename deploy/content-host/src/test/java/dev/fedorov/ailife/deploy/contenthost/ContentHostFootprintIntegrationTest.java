package dev.fedorov.ailife.deploy.contenthost;

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
 * ADR-0006 / #584 slice 3g — proves the Path B / B1 mechanism on the <b>second cold host-unit</b>, the
 * <b>Content</b> unit: the agent context ({@code creator-agent}), its domain-MCP ({@code mcp-creator}) and
 * the three trend capability-MCPs it binds ({@code mcp-youtube}, {@code mcp-reddit}, {@code mcp-feeds}) boot
 * <b>side-by-side in one JVM</b>, each on its own port, with zero change to the modules. This is the same
 * agent+MCP co-hosting the Docs unit (3f) proved, now over a larger cluster (one agent, four MCPs). The RAM
 * delta (one host vs five separate JVMs) is measured separately by {@code scripts/measure-footprint.sh} at
 * deploy (Mac).
 *
 * <p>{@code mcp-creator} is DB-bound, so it is wired to the shared Testcontainers PG with
 * {@code ddl-auto=none} (it applies no Liquibase at boot and holds no fatal startup query, so no schema is
 * needed to start the web + MCP servers). The three capability-MCPs are schema-less and {@code creator-agent}
 * owns no schema; the agent's MCP client is disabled here ({@code spring.ai.mcp.client.enabled=false}) so it
 * boots without the (not-co-hosted-in-this-proof) trend SSE endpoints — the agent→MCP SSE binding is a
 * deploy concern, not part of this co-residency proof. Only {@code creator-agent} carries an {@code AGENT.md}
 * (at {@code classpath:/}), so no per-agent manifest-path fix is needed for a single-agent host.
 */
class ContentHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each module's default Spring bean name (decapitalised {@code @SpringBootApplication} class),
        in the same order as {@link ContentHost#COLD_CONTENT}. */
    private static final List<String> APP_BEANS = List.of(
            "mcpCreatorApplication",
            "mcpYoutubeApplication",
            "mcpRedditApplication",
            "mcpFeedsApplication",
            "creatorAgentApplication");

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /** Base props common to every context (datasource is harmless for the schema-less MCPs + the agent). */
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

    /** Per-module boot needs, keyed by the launcher's short name. Each MCP re-supplies its MCP-server
        identity (the config-name skip dropped the module's own); the agent disables its MCP client. */
    private static Map<String, Object> extraFor(ContentHost.Hosted hosted) {
        Map<String, Object> p = baseProps();
        switch (hosted.name()) {
            case "mcp-creator", "mcp-youtube", "mcp-reddit", "mcp-feeds" -> {
                p.put("spring.ai.mcp.server.name", "ai-life-" + hosted.name() + "-hosted");
                p.put("spring.ai.mcp.server.version", "0.0.1");
                p.put("spring.ai.mcp.server.type", "ASYNC");
            }
            // co-residency proof only: don't require the agent's trend SSE endpoints at boot
            case "creator-agent" -> p.put("spring.ai.mcp.client.enabled", "false");
            default -> { /* no extra boot need */ }
        }
        return p;
    }

    @Test
    void contentColdHostContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (ContentHost.Hosted hosted : ContentHost.COLD_CONTENT) {
            ConfigurableApplicationContext ctx = ContentHost.boot(hosted, extraFor(hosted));
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(5);

        // all are live, independent contexts, all in THIS single JVM process — an agent and its four MCPs
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("the agent and its four MCPs run in this single JVM process")
                .isEqualTo(pid);

        // ...on five distinct web ports (each kept its own server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("the co-hosted agent + four MCPs bind distinct ports").hasSize(5);

        // ...each context loaded ONLY its own module's application bean (config-name skip did not cross-wire)
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String own = APP_BEANS.get(i);
            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", ContentHost.COLD_CONTENT.get(i).name(), own)
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
