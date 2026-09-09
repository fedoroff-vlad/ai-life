package dev.fedorov.ailife.deploy.agenthost;

import dev.fedorov.ailife.agents.calendar.CalendarAgentApplication;
import dev.fedorov.ailife.agents.coordinator.CoordinatorAgentApplication;
import dev.fedorov.ailife.agents.finance.FinanceAgentApplication;
import dev.fedorov.ailife.agents.notes.NotesAgentApplication;
import dev.fedorov.ailife.agents.researcher.ResearcherAgentApplication;
import dev.fedorov.ailife.agents.tasks.TasksAgentApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3d — Path B (process consolidation), approach B1, applied to the resident
 * <b>Agent-hot</b> tier.
 *
 * <p>Boots several LLM agent module contexts <b>in one JVM</b>, each an independent Spring context on
 * its own port. Nothing in the modules' contracts changes: agents keep their {@code /internal/*} and
 * inbound endpoints on the same URL/port. The win is that the ~300&nbsp;MB per-process JVM baseline
 * (JIT/code-cache, metaspace, GC, thread pools) is paid <b>once per host</b> instead of once per module.
 * Agents are LLM-bound and own no schema, so this host is kept separate from the DB-bound
 * Domain-MCP-hot host ({@code deploy/domain-mcp-host}) — see topology-map.md §Grouping principle 2.
 *
 * <p>Two collisions the shared classpath would otherwise cause are handled here:
 * <ul>
 *   <li>every module's {@code application.yml} sits at {@code classpath:/application.yml}, so each
 *       context is booted with a unique, non-existent {@code spring.config.name} that skips it — every
 *       value is supplied by {@link #structuralProps} or the deploy environment (the per-agent
 *       {@code @ConfigurationProperties} self-default to the compose hostnames);</li>
 *   <li>every agent's {@code AGENT.md} used to land at {@code classpath:/AGENT.md}; #584 3a-for-agents
 *       moved each to a per-agent {@code manifest/<name>/AGENT.md} (module {@code pom.xml}
 *       {@code <targetPath>}), and this launcher points each context's {@code agent.manifest-classpath}
 *       there so the manifest loader resolves the right persona.</li>
 * </ul>
 *
 * <p>Consuming these modules is possible because #584 slice 3a made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3d):</b> {@link #RESIDENT_HOT} is the always-on Agent-hot set (topology-map.md
 * §Resident). Co-residency is proven by {@code AgentHostFootprintIT}. Wiring each context's real
 * outbound config from the deploy environment (so {@link #main} is a drop-in for the separate service
 * processes) + the RAM measurement land at deploy (Mac; ADR-0006 slices 1/3).
 */
public final class AgentHost {

    private AgentHost() {}

    /**
     * One co-hosted agent: its {@code @SpringBootApplication} class, a short {@code name} (drives the
     * config-name skip {@code host-<name>} and the manifest path {@code manifest/<name>/AGENT.md}), and
     * the skills glob it scans ({@code null} for an agent with no skills, e.g. the coordinator).
     */
    public record Agent(Class<?> app, String name, String skillsGlob) {
        String configName() { return "host-" + name; }
        String manifestClasspath() { return "manifest/" + name + "/AGENT.md"; }
    }

    /**
     * The resident Agent-hot set — the always-in-memory agent contexts (topology-map.md §Resident:
     * calendar · finance · tasks · notes · coordinator · researcher). Cold agents (creator, stylist,
     * chef, nutritionist, briefing, docs, travel) group into their cold host-units as consolidation
     * rolls out (ADR-0006 item 4).
     */
    public static final List<Agent> RESIDENT_HOT = List.of(
            new Agent(CalendarAgentApplication.class, "calendar-agent", "classpath*:skills/calendar/*/SKILL.md"),
            new Agent(FinanceAgentApplication.class, "finance-agent", "classpath*:skills/finance/*/SKILL.md"),
            new Agent(TasksAgentApplication.class, "tasks-agent", "classpath*:skills/tasks/*/SKILL.md"),
            new Agent(NotesAgentApplication.class, "notes-agent", "classpath*:skills/knowledge/*/SKILL.md"),
            new Agent(CoordinatorAgentApplication.class, "coordinator-agent", null),
            new Agent(ResearcherAgentApplication.class, "researcher-agent", "classpath*:skills/researcher/*/SKILL.md"));

    /**
     * The properties that are structural (identical in test and deploy): skip the module's
     * {@code application.yml}, run reactive, and point the manifest/skills loaders at the per-agent
     * classpath locations. Outbound URLs, ports and the MCP client toggle come from the caller / env.
     */
    public static Map<String, Object> structuralProps(Agent agent) {
        Map<String, Object> p = new HashMap<>();
        // unique + non-existent → the module's classpath application.yml is skipped, so co-hosted
        // agents never fight over classpath:/application.yml.
        p.put("spring.config.name", agent.configName());
        p.put("spring.main.web-application-type", "reactive");
        // per-agent manifest path (moved off classpath:/AGENT.md to avoid the co-host collision)
        p.put("agent.manifest-classpath", agent.manifestClasspath());
        if (agent.skillsGlob() != null) {
            p.put("agent.skills-classpath", agent.skillsGlob());
        }
        return p;
    }

    /** Boot one agent context in the current JVM. {@code extra} adds/overrides ports, URLs, toggles. */
    public static ConfigurableApplicationContext boot(Agent agent, Map<String, Object> extra) {
        Map<String, Object> props = structuralProps(agent);
        props.putAll(extra);
        return new SpringApplicationBuilder(agent.app()).properties(props).run();
    }

    public static void main(String[] args) {
        // Deploy path: each context reads its outbound config (the per-agent env vars the standalone
        // services already use) + keeps its port; the MCP client stays enabled. Wiring env → per-context
        // property map lands with the deployable-host rollout; co-residency is proven by the IT.
        for (Agent a : RESIDENT_HOT) {
            new SpringApplicationBuilder(a.app()).properties(structuralProps(a)).run(args);
        }
    }
}
