package dev.fedorov.ailife.deploy.agenthost;

import dev.fedorov.ailife.agents.calendar.CalendarAgentApplication;
import dev.fedorov.ailife.agents.coordinator.CoordinatorAgentApplication;
import dev.fedorov.ailife.agents.finance.FinanceAgentApplication;
import dev.fedorov.ailife.agents.notes.NotesAgentApplication;
import dev.fedorov.ailife.agents.researcher.ResearcherAgentApplication;
import dev.fedorov.ailife.agents.tasks.TasksAgentApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3d (co-residency) + 3q (per-module config) — Path B (process consolidation),
 * approach B1, applied to the resident <b>Agent-hot</b> tier.
 *
 * <p>Boots several LLM agent module contexts <b>in one JVM</b>, each an independent Spring context on
 * its own port. Nothing in the modules' contracts changes: agents keep their {@code /internal/*} and
 * inbound endpoints on the same URL/port. The win is that the ~300&nbsp;MB per-process JVM baseline
 * (JIT/code-cache, metaspace, GC, thread pools) is paid <b>once per host</b> instead of once per module.
 * Agents are LLM-bound and own no schema, so this host is kept separate from the DB-bound
 * Domain-MCP-hot host ({@code deploy/domain-mcp-host}) — see topology-map.md §Grouping principle 2.
 *
 * <p><b>How the config collision is solved (slice 3q).</b> Every module's configuration sits at
 * {@code classpath:/application.yml}, so on the host's shared classloader six contexts would fight over
 * one file. Slice 3d sidestepped that with a unique, non-existent {@code spring.config.name} and then
 * had to re-supply, in Java, everything the skipped yml would have said: the reactive web stack, the
 * per-agent manifest path, and each agent's skills glob. That is a module's contract copied into the
 * launcher — and it was already a duplicate, because every agent's own yml declares all three.
 *
 * <p>So instead each agent's pom lands a <b>copy</b> of its own {@code application.yml} at
 * {@code config/<name>/} (one source file, two classpath locations — the standalone {@code -exec.jar}
 * still resolves {@code classpath:/application.yml} unchanged), and each context is booted with
 * {@code spring.config.location} pointing there. The launcher re-supplies <b>nothing</b>: the yml is the
 * single source for the web stack, {@code agent.manifest-classpath} and {@code agent.skills-classpath}
 * — including {@code coordinator-agent}, whose yml deliberately leaves the skills glob unset to keep its
 * registry empty, which the old {@code null} in this file duplicated. Same precedent as slice 3d's
 * per-agent {@code manifest/<name>/AGENT.md}: the file stays in the module, only its classpath location
 * changes.
 *
 * <p><b>Why this tier needs no auto-configuration excludes.</b> Co-hosting merges classpaths, which on
 * the Domain-MCP-hot host handed the schema-less capability MCPs their siblings' JPA starters and killed
 * them at deploy (slice 3o). All six agents here are schema-less, so there is no JPA on this host's
 * classpath to inherit — the mixing is what hurts, not the consolidating. Adding a DB-bound member to
 * this host would change that.
 *
 * <p>Consuming these modules is possible because #584 slice 3a made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 */
public final class AgentHost {

    private AgentHost() {}

    /**
     * One co-hosted agent: its {@code @SpringBootApplication} class and the module name, which is also
     * the per-module classpath folder its {@code application.yml} copy lands in (pom
     * {@code targetPath}).
     *
     * <p>The old {@code skillsGlob} field is gone: it duplicated a value every agent's yml already
     * declares, so the two could drift and only the yml was ever right.
     */
    public record Agent(Class<?> app, String name) {

        /**
         * Where this agent's own {@code application.yml} copy lives on the classpath. Deliberately not
         * {@code optional:} — if an agent's pom loses the per-module resource block, the context must
         * fail fast rather than start on a sibling's configuration (which, for an agent, would mean
         * booting someone else's persona).
         */
        public String configLocation() {
            return "classpath:/config/" + name + "/";
        }
    }

    /**
     * The resident Agent-hot set — the always-in-memory agent contexts (topology-map.md §Resident:
     * calendar · finance · tasks · notes · coordinator · researcher). Cold agents (creator, stylist,
     * chef, nutritionist, briefing, docs, travel) group into their cold host-units as consolidation
     * rolls out (ADR-0006 item 4).
     */
    public static final List<Agent> RESIDENT_HOT = List.of(
            new Agent(CalendarAgentApplication.class, "calendar-agent"),
            new Agent(FinanceAgentApplication.class, "finance-agent"),
            new Agent(TasksAgentApplication.class, "tasks-agent"),
            new Agent(NotesAgentApplication.class, "notes-agent"),
            new Agent(CoordinatorAgentApplication.class, "coordinator-agent"),
            new Agent(ResearcherAgentApplication.class, "researcher-agent"));

    /**
     * Boot one agent context in the current JVM, reading that agent's own {@code application.yml}.
     *
     * <p>{@code overrides} are passed as <b>command-line arguments</b>, not as
     * {@link SpringApplicationBuilder#properties}: Boot puts builder properties in the
     * {@code defaultProperties} source, which ranks <i>below</i> config files, so a caller's value would
     * lose to the yml.
     */
    public static ConfigurableApplicationContext boot(Agent agent, Map<String, Object> overrides) {
        return new SpringApplicationBuilder(agent.app())
                .run(launchArgs(agent, overrides, new String[0]));
    }

    /**
     * The single place an agent's launch arguments are assembled, shared by {@link #boot} and
     * {@link #main} on purpose: on the Domain-MCP-hot host two separate wiring paths let the IT pass
     * while the deploy died (slice 3o).
     */
    private static String[] launchArgs(Agent agent, Map<String, Object> overrides, String[] extra) {
        List<String> args = new ArrayList<>();
        args.add("--spring.config.location=" + agent.configLocation());
        // The logging system initialises ONCE per JVM, so the first context's spring.application.name
        // would prefix every other context's lines — six agents all logging as [calendar-agent]. Drop
        // the field rather than let five in six lie; the logger name still carries the module.
        args.add("--logging.include-application-name=false");
        overrides.forEach((key, value) -> args.add("--" + key + "=" + value));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    /**
     * Deploy entry point: boot every resident agent in this JVM. Each reads its own yml, so the
     * per-agent env vars the standalone services already use keep working untouched and each context
     * keeps the port its module declares. Any extra args are forwarded to every context.
     */
    public static void main(String[] args) {
        for (Agent agent : RESIDENT_HOT) {
            new SpringApplicationBuilder(agent.app()).run(launchArgs(agent, Map.of(), args));
        }
    }
}
