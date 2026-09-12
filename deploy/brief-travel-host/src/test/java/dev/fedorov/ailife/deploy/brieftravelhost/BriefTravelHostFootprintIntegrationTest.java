package dev.fedorov.ailife.deploy.brieftravelhost;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
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
 * ADR-0006 / #584 slice 3i — proves the Path B / B1 mechanism on the <b>fourth cold host-unit</b>, the
 * <b>Brief+Travel</b> unit: two agents ({@code briefing-agent}, {@code travel-agent}), their domain-MCPs
 * ({@code mcp-briefing}, {@code mcp-travel}) and the two capability-MCPs they bind ({@code mcp-weather},
 * {@code mcp-travel-search}) boot <b>side-by-side in one JVM</b>, each on its own port, with zero change to
 * the modules. The RAM delta (one host vs six separate JVMs) is measured separately by
 * {@code scripts/measure-footprint.sh} at deploy (Mac).
 *
 * <p><b>Multi-agent unit</b> (like Lifestyle, 3h), so — like the resident Agent-hot host (3d) — the key
 * extra assertion is that each of the two agent contexts loaded its <b>own</b> {@code AGENT.md} persona
 * (not a neighbour's): the {@code classpath:/AGENT.md} collision is resolved by the per-agent
 * {@code manifest/<name>/AGENT.md} path.
 *
 * <p>{@code mcp-briefing} + {@code mcp-travel} are DB-bound, so they are wired to the shared Testcontainers
 * PG with {@code ddl-auto=none} (they apply no Liquibase at boot and hold no fatal startup query, so no
 * schema is needed to start the web + MCP servers). The two capability-MCPs are schema-less and the two
 * agents own no schema; each agent's MCP client is disabled here ({@code spring.ai.mcp.client.enabled=false})
 * so it boots without the (not-co-hosted-in-this-proof) MCP SSE endpoints — the agent→MCP SSE binding is a
 * deploy concern, not part of this co-residency proof.
 */
class BriefTravelHostFootprintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each module's default Spring bean name, in the same order as {@link BriefTravelHost#COLD_BRIEF_TRAVEL}. */
    private static final List<String> APP_BEANS = List.of(
            "mcpBriefingApplication",
            "mcpTravelApplication",
            "mcpWeatherApplication",
            "mcpTravelSearchApplication",
            "briefingAgentApplication",
            "travelAgentApplication");

    /** Agent bean name → the persona its own AGENT.md declares (proves the per-agent manifest-path fix). */
    private static final Map<String, String> AGENT_PERSONA = Map.of(
            "briefingAgentApplication", "briefing",
            "travelAgentApplication", "travel");

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /** Base props common to every context (datasource is harmless for the schema-less MCPs + the agents). */
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
        identity (the config-name skip dropped the module's own); each agent disables its MCP client. */
    private static Map<String, Object> extraFor(BriefTravelHost.Hosted hosted) {
        Map<String, Object> p = baseProps();
        switch (hosted.name()) {
            case "mcp-briefing", "mcp-travel", "mcp-weather", "mcp-travel-search" -> {
                p.put("spring.ai.mcp.server.name", "ai-life-" + hosted.name() + "-hosted");
                p.put("spring.ai.mcp.server.version", "0.0.1");
                p.put("spring.ai.mcp.server.type", "ASYNC");
            }
            // co-residency proof only: don't require the agents' MCP SSE endpoints at boot
            case "briefing-agent", "travel-agent" -> p.put("spring.ai.mcp.client.enabled", "false");
            default -> { /* no extra boot need */ }
        }
        return p;
    }

    @Test
    void briefTravelColdHostContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (BriefTravelHost.Hosted hosted : BriefTravelHost.COLD_BRIEF_TRAVEL) {
            ConfigurableApplicationContext ctx = BriefTravelHost.boot(hosted, extraFor(hosted));
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(6);

        // all are live, independent contexts, all in THIS single JVM process — two agents + four MCPs
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("the two agents and their four MCPs run in this single JVM process")
                .isEqualTo(pid);

        // ...on six distinct web ports (each kept its own server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("the co-hosted agents + MCPs bind distinct ports").hasSize(6);

        // ...each context loaded ONLY its own application bean (config-name skip did not cross-wire), and
        // each agent loaded its OWN AGENT.md persona (proves the per-agent manifest-path fix, #584 3d/3i).
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            String own = APP_BEANS.get(i);

            assertThat(ctx.containsBean(own))
                    .as("%s owns its application bean %s", BriefTravelHost.COLD_BRIEF_TRAVEL.get(i).name(), own)
                    .isTrue();

            String persona = AGENT_PERSONA.get(own);
            if (persona != null) {
                assertThat(ctx.getBean(AgentManifest.class).name())
                        .as("%s loaded its own AGENT.md persona (no classpath:/AGENT.md cross-load)", own)
                        .isEqualTo(persona);
            }

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
