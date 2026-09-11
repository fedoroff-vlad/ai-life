package dev.fedorov.ailife.deploy.contenthost;

import dev.fedorov.ailife.agents.creator.CreatorAgentApplication;
import dev.fedorov.ailife.mcp.creator.McpCreatorApplication;
import dev.fedorov.ailife.mcp.feeds.McpFeedsApplication;
import dev.fedorov.ailife.mcp.reddit.McpRedditApplication;
import dev.fedorov.ailife.mcp.youtube.McpYoutubeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3g — Path B (process consolidation), approach B1, applied to the <b>second cold
 * host-unit</b>: the <b>Content</b> unit.
 *
 * <p>Boots the Content cluster's contexts — {@code creator-agent} (the LLM specialist), its domain-MCP
 * {@code mcp-creator}, and the three trend capability-MCPs it binds ({@code mcp-youtube}, {@code mcp-reddit},
 * {@code mcp-feeds}) — <b>in one JVM</b>, each an independent Spring context on its own port. Nothing in
 * the modules' contracts changes: the agent keeps its {@code /agents/creator/*} endpoints and each MCP keeps
 * its {@code /internal/*} passthrough + MCP/SSE surface on the same URLs/ports.
 *
 * <p><b>What makes a cold host different from the resident tier:</b> resident hosts keep the agent tier and
 * the MCP tier in separate JVMs (topology-map §Grouping 2 — different resource profiles, both
 * always-in-memory). Cold hosts instead group by <b>co-usage affinity</b> (§Grouping 1): a whole cold
 * cluster is started/stopped together, so co-hosting the agent with the MCPs it fans out to reclaims the
 * ~300&nbsp;MB per-process JVM baseline for the entire unit at once (here 5 JVMs → 1). This is the same
 * agent+MCP co-hosting the Docs unit (3f) proved, now over a larger cluster (one agent, four MCPs).
 *
 * <p>Two shared-classpath concerns are handled by the launcher (as in {@code DocsHost}):
 * <ul>
 *   <li><b>{@code application.yml}</b> (all at {@code classpath:/application.yml}) — each context is booted
 *       with a unique, non-existent {@code spring.config.name} that skips it; the per-module
 *       {@code @ConfigurationProperties} self-default to the compose hostnames/ports, and the deploy env
 *       supplies the rest;</li>
 *   <li><b>web type</b> — the config-name skip also drops each module's own
 *       {@code spring.main.web-application-type}, so the launcher re-supplies it. {@code creator-agent} and
 *       the three capability-MCPs are webflux-only → {@code reactive}; {@code mcp-creator} carries both
 *       {@code spring-web} and {@code webflux} (like {@code mcp-docs}), so it is pinned {@code servlet}
 *       rather than left to an ambiguous auto-detect.</li>
 * </ul>
 * The single agent also needs its {@code agent.skills-classpath} re-supplied: it lives in the skipped
 * {@code application.yml} and the runtime <b>fails startup</b> if {@code AGENT.md} declares skills the
 * (then empty) registry never loaded. Only one {@code AGENT.md} is on this host's classpath
 * ({@code creator-agent}'s; the four MCPs have none), so the resident tier's per-agent manifest-path fix
 * (#584 3d) is not needed here — the default {@code classpath:/AGENT.md} is unambiguous.
 *
 * <p>Consuming these modules is possible because #584 slice 3g made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3g):</b> {@link #COLD_CONTENT} is the Content cold host-unit (topology-map.md §Cold).
 * Co-residency is proven by {@code ContentHostFootprintIT}. Wiring each context's real config from the
 * deploy environment (so {@link #main} is a drop-in for the separate service processes), the agent→MCP SSE
 * bindings, and the on-demand start/stop lifecycle land at deploy (Mac; ADR-0006 slices 1/3 + LC-2).
 */
public final class ContentHost {

    private ContentHost() {}

    /**
     * One co-hosted module: its {@code @SpringBootApplication} class, a short {@code name} (drives the
     * config-name skip {@code host-<name>}), its {@code webType} ({@code reactive}/{@code servlet}), and
     * an optional {@code skillsGlob} (non-null only for an agent — re-supplied because the config-name
     * skip drops the module's own {@code agent.skills-classpath}).
     */
    public record Hosted(Class<?> app, String name, String webType, String skillsGlob) {
        String configName() { return "host-" + name; }
    }

    /**
     * The Content cold host-unit — the agent + its domain-MCP + the three trend capability-MCPs it binds
     * (topology-map.md §Cold: creator-agent · mcp-creator · mcp-youtube · mcp-reddit · mcp-feeds). Other
     * cold units (Lifestyle, Brief+Travel, Finance-aux, Coach) each get their own launcher module as
     * consolidation rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> COLD_CONTENT = List.of(
            new Hosted(McpCreatorApplication.class, "mcp-creator", "servlet", null),
            new Hosted(McpYoutubeApplication.class, "mcp-youtube", "reactive", null),
            new Hosted(McpRedditApplication.class, "mcp-reddit", "reactive", null),
            new Hosted(McpFeedsApplication.class, "mcp-feeds", "reactive", null),
            new Hosted(CreatorAgentApplication.class, "creator-agent", "reactive",
                    "classpath*:skills/creator/*/SKILL.md"));

    /**
     * The properties that are structural (identical in test and deploy): skip the module's
     * {@code application.yml}, pin the web type, and (for an agent) re-supply the manifest + skills
     * classpath. Datasource, ports, external URLs and the MCP-client toggle come from the caller / env.
     */
    public static Map<String, Object> structuralProps(Hosted hosted) {
        Map<String, Object> p = new HashMap<>();
        // unique + non-existent → the module's classpath application.yml is skipped, so co-hosted
        // modules never fight over classpath:/application.yml.
        p.put("spring.config.name", hosted.configName());
        // re-supplied because the config-name skip dropped the module's own web-type declaration
        p.put("spring.main.web-application-type", hosted.webType());
        if (hosted.skillsGlob() != null) {
            // one AGENT.md on this host's classpath → the default classpath:/AGENT.md is unambiguous;
            // but the skills glob lives in the skipped yml and startup fails if AGENT.md declares skills
            // the registry never loaded — so re-supply it.
            p.put("agent.manifest-classpath", "AGENT.md");
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
        for (Hosted h : COLD_CONTENT) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
