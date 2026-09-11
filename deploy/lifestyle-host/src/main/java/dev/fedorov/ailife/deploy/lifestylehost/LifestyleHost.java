package dev.fedorov.ailife.deploy.lifestylehost;

import dev.fedorov.ailife.agents.chef.ChefAgentApplication;
import dev.fedorov.ailife.agents.nutritionist.NutritionistAgentApplication;
import dev.fedorov.ailife.agents.stylist.StylistAgentApplication;
import dev.fedorov.ailife.mcp.fooddata.McpFoodDataApplication;
import dev.fedorov.ailife.mcp.imagegen.McpImageGenApplication;
import dev.fedorov.ailife.mcp.nutrition.McpNutritionApplication;
import dev.fedorov.ailife.mcp.wardrobe.McpWardrobeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3h — Path B (process consolidation), approach B1, applied to the <b>third cold
 * host-unit</b>: the <b>Lifestyle</b> unit.
 *
 * <p>Boots the Lifestyle cluster's contexts — three agents ({@code stylist-agent}, {@code nutritionist-agent},
 * {@code chef-agent}), their domain-MCPs ({@code mcp-wardrobe}, {@code mcp-nutrition}) and the two
 * capability-MCPs they bind ({@code mcp-image-gen}, {@code mcp-food-data}) — <b>in one JVM</b>, each an
 * independent Spring context on its own port. Nothing in the modules' contracts changes: agents keep their
 * {@code /agents/*} endpoints and each MCP keeps its {@code /internal/*} passthrough + MCP/SSE surface on
 * the same URLs/ports.
 *
 * <p><b>What makes a cold host different from the resident tier:</b> resident hosts keep the agent tier and
 * the MCP tier in separate JVMs (topology-map §Grouping 2). Cold hosts instead group by <b>co-usage
 * affinity</b> (§Grouping 1): a whole cold cluster is started/stopped together, so co-hosting the agents
 * with the MCPs they fan out to reclaims the ~300&nbsp;MB per-process JVM baseline for the entire unit at
 * once (here 7 JVMs → 1).
 *
 * <p><b>First cold unit with more than one agent.</b> The Docs (3f) and Content (3g) units each had a single
 * agent, so their one {@code AGENT.md} at {@code classpath:/AGENT.md} was unambiguous. Here three agent
 * contexts share the host's classloader, so — exactly as the resident Agent-hot host (#584 3d) — each
 * {@code AGENT.md} is landed at a per-agent {@code manifest/<name>/AGENT.md} (module {@code pom.xml}
 * {@code <targetPath>}; the file stays at the module root) and the launcher points each context's
 * {@code agent.manifest-classpath} there so every context resolves its own persona. (The skills classpath
 * is shared by design — {@code nutritionist-agent} and {@code chef-agent} both scan
 * {@code skills/nutrition/*}, as they do standalone.)
 *
 * <p>Two more shared-classpath concerns are handled by the launcher:
 * <ul>
 *   <li><b>{@code application.yml}</b> (all at {@code classpath:/application.yml}) — each context is booted
 *       with a unique, non-existent {@code spring.config.name} that skips it; the per-module
 *       {@code @ConfigurationProperties} self-default to the compose hostnames/ports, and the deploy env
 *       supplies the rest;</li>
 *   <li><b>web type</b> — the config-name skip also drops each module's own
 *       {@code spring.main.web-application-type}. The three agents and the two capability-MCPs are
 *       webflux-only → {@code reactive}; {@code mcp-wardrobe} and {@code mcp-nutrition} carry both
 *       {@code spring-web} and {@code webflux} (like {@code mcp-docs}/{@code mcp-creator}), so they are
 *       pinned {@code servlet} rather than left to an ambiguous auto-detect.</li>
 * </ul>
 * Each agent also needs its {@code agent.skills-classpath} re-supplied (it lives in the skipped
 * {@code application.yml} and the runtime <b>fails startup</b> if {@code AGENT.md} declares skills the then
 * empty registry never loaded).
 *
 * <p>Consuming these modules is possible because #584 slice 3h made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3h):</b> {@link #COLD_LIFESTYLE} is the Lifestyle cold host-unit (topology-map.md
 * §Cold). Co-residency is proven by {@code LifestyleHostFootprintIT}. Wiring each context's real config
 * from the deploy environment (so {@link #main} is a drop-in for the separate service processes), the
 * agent→MCP SSE bindings, and the on-demand start/stop lifecycle land at deploy (Mac; ADR-0006 slices 1/3
 * + LC-2).
 */
public final class LifestyleHost {

    private LifestyleHost() {}

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
     * The Lifestyle cold host-unit — three agents + their domain-MCPs + the capability-MCPs they bind
     * (topology-map.md §Cold: stylist-agent · mcp-wardrobe · mcp-image-gen · nutritionist-agent ·
     * chef-agent · mcp-nutrition · mcp-food-data). Other cold units (Brief+Travel, Finance-aux, Coach)
     * each get their own launcher module as consolidation rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> COLD_LIFESTYLE = List.of(
            new Hosted(McpWardrobeApplication.class, "mcp-wardrobe", "servlet", null),
            new Hosted(McpImageGenApplication.class, "mcp-image-gen", "reactive", null),
            new Hosted(McpNutritionApplication.class, "mcp-nutrition", "servlet", null),
            new Hosted(McpFoodDataApplication.class, "mcp-food-data", "reactive", null),
            new Hosted(StylistAgentApplication.class, "stylist-agent", "reactive",
                    "classpath*:skills/stylist/*/SKILL.md"),
            new Hosted(NutritionistAgentApplication.class, "nutritionist-agent", "reactive",
                    "classpath*:skills/nutrition/*/SKILL.md"),
            new Hosted(ChefAgentApplication.class, "chef-agent", "reactive",
                    "classpath*:skills/nutrition/*/SKILL.md"));

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
            // three agents share this host, so unlike Docs/Content the default path is ambiguous)
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
        for (Hosted h : COLD_LIFESTYLE) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
