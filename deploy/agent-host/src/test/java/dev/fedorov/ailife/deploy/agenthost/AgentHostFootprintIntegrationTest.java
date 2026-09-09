package dev.fedorov.ailife.deploy.agenthost;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0006 / #584 slice 3d — proves the Path B / B1 mechanism on the resident <b>Agent-hot</b> set: the
 * six always-on agent module contexts (calendar · finance · tasks · notes · coordinator · researcher)
 * boot <b>side-by-side in one JVM</b>, each on its own port, with zero contract change. This is the
 * consolidation building block; the RAM delta (one host vs six separate JVMs) is measured separately by
 * {@code scripts/measure-footprint.sh} at deploy (Mac).
 *
 * <p>Agents own no schema, so no database is needed — the test tags {@code it} directly (slow lane,
 * failsafe/verify only) rather than starting a Testcontainers PG. Each context boots with its MCP client
 * disabled (the modules' own {@code *_MCP_CLIENT_ENABLED} test toggle — tasks-agent's client is
 * fail-fast at boot) and a random port; the manifest/skills loaders read the per-agent classpath paths
 * the launcher supplies. The key assertion is that each context loaded its <b>own</b> {@code AGENT.md}
 * persona (not a neighbour's) — i.e. the classpath:/AGENT.md collision is resolved.
 */
@Tag("it")
class AgentHostFootprintIntegrationTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** Each hot agent's default Spring bean name + the persona its own AGENT.md declares. */
    private record Expect(String appBean, String manifestName) {}

    private static final List<Expect> EXPECT = List.of(
            new Expect("calendarAgentApplication", "calendar"),
            new Expect("financeAgentApplication", "finance"),
            new Expect("tasksAgentApplication", "tasks"),
            new Expect("notesAgentApplication", "notes"),
            new Expect("coordinatorAgentApplication", "coordinator"),
            new Expect("researcherAgentApplication", "researcher"));

    @AfterAll
    static void closeAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
    }

    /** Random port + MCP client off — enough to prove co-residency without the rest of the stack. */
    private static Map<String, Object> testExtra() {
        return Map.of(
                "server.port", "0",
                "spring.ai.mcp.client.enabled", "false");
    }

    @Test
    void residentHotAgentContextsCoexistInOneJvmOnDistinctPorts() {
        long pid = ProcessHandle.current().pid();

        List<ConfigurableApplicationContext> booted = new ArrayList<>();
        for (AgentHost.Agent agent : AgentHost.RESIDENT_HOT) {
            ConfigurableApplicationContext ctx = AgentHost.boot(agent, testExtra());
            booted.add(ctx);
            CONTEXTS.add(ctx);
        }

        assertThat(booted).hasSize(6);

        // all six are live, independent contexts, all in THIS single JVM process
        assertThat(booted).allSatisfy(ctx -> assertThat(ctx.isRunning()).isTrue());
        assertThat(ProcessHandle.current().pid())
                .as("all co-hosted agent contexts run in this single JVM process")
                .isEqualTo(pid);

        // ...on six distinct web ports (each agent kept its own reactive server, no port collision)
        Set<Integer> ports = new LinkedHashSet<>();
        for (ConfigurableApplicationContext ctx : booted) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(port).isPositive();
            ports.add(port);
        }
        assertThat(ports).as("each co-hosted agent binds a distinct port").hasSize(6);

        // ...each context loaded ONLY its own module's application bean AND its OWN AGENT.md persona
        // (proves both the application.yml config-name skip and the per-agent manifest-path fix).
        for (int i = 0; i < booted.size(); i++) {
            ConfigurableApplicationContext ctx = booted.get(i);
            Expect e = EXPECT.get(i);

            assertThat(ctx.containsBean(e.appBean()))
                    .as("%s owns its application bean %s", e.manifestName(), e.appBean())
                    .isTrue();
            assertThat(ctx.getBean(AgentManifest.class).name())
                    .as("%s loaded its own AGENT.md persona (no classpath:/AGENT.md cross-load)", e.manifestName())
                    .isEqualTo(e.manifestName());

            for (Expect other : EXPECT) {
                if (!other.appBean().equals(e.appBean())) {
                    assertThat(ctx.containsBean(other.appBean()))
                            .as("%s must not carry sibling bean %s", e.appBean(), other.appBean())
                            .isFalse();
                }
            }
        }
    }
}
