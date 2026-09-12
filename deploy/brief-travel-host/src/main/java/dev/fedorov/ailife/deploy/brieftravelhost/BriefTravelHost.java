package dev.fedorov.ailife.deploy.brieftravelhost;

import dev.fedorov.ailife.agents.briefing.BriefingAgentApplication;
import dev.fedorov.ailife.agents.travel.TravelAgentApplication;
import dev.fedorov.ailife.mcp.briefing.McpBriefingApplication;
import dev.fedorov.ailife.mcp.travel.McpTravelApplication;
import dev.fedorov.ailife.mcp.travelsearch.McpTravelSearchApplication;
import dev.fedorov.ailife.mcp.weather.McpWeatherApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3i — Path B (process consolidation), approach B1, applied to the <b>fourth cold
 * host-unit</b>: the <b>Brief+Travel</b> unit.
 *
 * <p>Boots the Brief+Travel cluster's contexts — two agents ({@code briefing-agent}, {@code travel-agent}),
 * their domain-MCPs ({@code mcp-briefing}, {@code mcp-travel}) and the two capability-MCPs they bind
 * ({@code mcp-weather}, {@code mcp-travel-search}) — <b>in one JVM</b>, each an independent Spring context
 * on its own port. Nothing in the modules' contracts changes: agents keep their {@code /agents/*} endpoints
 * and each MCP keeps its {@code /internal/*} passthrough + MCP/SSE surface on the same URLs/ports.
 *
 * <p><b>What makes a cold host different from the resident tier:</b> resident hosts keep the agent tier and
 * the MCP tier in separate JVMs (topology-map §Grouping 2). Cold hosts instead group by <b>co-usage
 * affinity</b> (§Grouping 1): a whole cold cluster is started/stopped together, so co-hosting the agents
 * with the MCPs they fan out to reclaims the ~300&nbsp;MB per-process JVM baseline for the entire unit at
 * once (here 6 JVMs → 1).
 *
 * <p><b>Multi-agent unit</b> (like Lifestyle, 3h). Two agent contexts share the host's classloader, so —
 * exactly as the resident Agent-hot host (#584 3d) — each {@code AGENT.md} is landed at a per-agent
 * {@code manifest/<name>/AGENT.md} (module {@code pom.xml} {@code <targetPath>}; the file stays at the
 * module root) and the launcher points each context's {@code agent.manifest-classpath} there so every
 * context resolves its own persona.
 *
 * <p>Two more shared-classpath concerns are handled by the launcher:
 * <ul>
 *   <li><b>{@code application.yml}</b> (all at {@code classpath:/application.yml}) — each context is booted
 *       with a unique, non-existent {@code spring.config.name} that skips it; the per-module
 *       {@code @ConfigurationProperties} self-default to the compose hostnames/ports, and the deploy env
 *       supplies the rest;</li>
 *   <li><b>web type</b> — the config-name skip also drops each module's own
 *       {@code spring.main.web-application-type}. The two agents and the two capability-MCPs are
 *       webflux-only → {@code reactive}; {@code mcp-briefing} and {@code mcp-travel} carry both
 *       {@code spring-web} and {@code webflux} (like {@code mcp-docs}/{@code mcp-creator}), so they are
 *       pinned {@code servlet} rather than left to an ambiguous auto-detect.</li>
 * </ul>
 * Each agent also needs its {@code agent.skills-classpath} re-supplied (it lives in the skipped
 * {@code application.yml} and the runtime <b>fails startup</b> if {@code AGENT.md} declares skills the then
 * empty registry never loaded).
 *
 * <p>Consuming these modules is possible because each module's main Maven artifact is a plain classes jar
 * (the executable moved to the {@code -exec} classifier — {@code mcp-briefing} + {@code mcp-travel} in the
 * #584 3a pilot, the rest in 3i).
 *
 * <p><b>Slice status (3i):</b> {@link #COLD_BRIEF_TRAVEL} is the Brief+Travel cold host-unit
 * (topology-map.md §Cold). Co-residency is proven by {@code BriefTravelHostFootprintIT}. Wiring each
 * context's real config from the deploy environment (so {@link #main} is a drop-in for the separate service
 * processes), the agent→MCP SSE bindings, and the on-demand start/stop lifecycle land at deploy (Mac;
 * ADR-0006 slices 1/3 + LC-2).
 */
public final class BriefTravelHost {

    private BriefTravelHost() {}

    /**
     * One co-hosted module: its {@code @SpringBootApplication} class, a short {@code name} (drives the
     * config-name skip {@code host-<name>} and, for an agent, the manifest path
     * {@code manifest/<name>/AGENT.md}), its {@code webType} ({@code reactive}/{@code servlet}), and an
     * optional {@code skillsGlob} (non-null only for an agent — re-supplied because the config-name skip
     * drops the module's own {@code agent.skills-classpath}).
     */
    public record Hosted(Class<?> app, String name, String webType, String skillsGlob) {
        String configName() { return "host-" + name; }
        String manifestClasspath() { return "manifest/" + name + "/AGENT.md"; }
        boolean isAgent() { return skillsGlob != null; }
    }

    /**
     * The Brief+Travel cold host-unit — two agents + their domain-MCPs + the capability-MCPs they bind
     * (topology-map.md §Cold: briefing-agent · mcp-briefing · mcp-weather · travel-agent · mcp-travel ·
     * mcp-travel-search). The remaining cold units (Finance-aux, Coach) each get their own launcher module
     * as consolidation rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> COLD_BRIEF_TRAVEL = List.of(
            new Hosted(McpBriefingApplication.class, "mcp-briefing", "servlet", null),
            new Hosted(McpTravelApplication.class, "mcp-travel", "servlet", null),
            new Hosted(McpWeatherApplication.class, "mcp-weather", "reactive", null),
            new Hosted(McpTravelSearchApplication.class, "mcp-travel-search", "reactive", null),
            new Hosted(BriefingAgentApplication.class, "briefing-agent", "reactive",
                    "classpath*:skills/briefing/*/SKILL.md"),
            new Hosted(TravelAgentApplication.class, "travel-agent", "reactive",
                    "classpath*:skills/travel/*/SKILL.md"));

    /**
     * The properties that are structural (identical in test and deploy): skip the module's
     * {@code application.yml}, pin the web type, and (for an agent) point the manifest loader at the
     * per-agent classpath path and re-supply the skills glob. Datasource, ports, external URLs and the
     * MCP-client toggle come from the caller / env.
     */
    public static Map<String, Object> structuralProps(Hosted hosted) {
        Map<String, Object> p = new HashMap<>();
        // unique + non-existent → the module's classpath application.yml is skipped, so co-hosted
        // modules never fight over classpath:/application.yml.
        p.put("spring.config.name", hosted.configName());
        // re-supplied because the config-name skip dropped the module's own web-type declaration
        p.put("spring.main.web-application-type", hosted.webType());
        if (hosted.isAgent()) {
            // per-agent manifest path (moved off classpath:/AGENT.md to avoid the co-host collision —
            // two agents share this host, so unlike Docs/Content the default path is ambiguous)
            p.put("agent.manifest-classpath", hosted.manifestClasspath());
            // the skills glob lives in the skipped yml and startup fails if AGENT.md declares skills the
            // registry never loaded — so re-supply it.
            p.put("agent.skills-classpath", hosted.skillsGlob());
        }
        return p;
    }

    /** Boot one module context in the current JVM. {@code extra} adds/overrides datasource, ports, URLs. */
    public static ConfigurableApplicationContext boot(Hosted hosted, Map<String, Object> extra) {
        Map<String, Object> props = structuralProps(hosted);
        props.putAll(extra);
        return new SpringApplicationBuilder(hosted.app()).properties(props).run();
    }

    public static void main(String[] args) {
        // Deploy path: each context reads its own config from the environment (the per-module env vars
        // the standalone services already use), keeping its port. Wiring env → per-context property map
        // + the agent→co-hosted-MCP SSE bindings land with the deployable-host rollout; the co-residency
        // mechanism is proven by the IT.
        for (Hosted h : COLD_BRIEF_TRAVEL) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
