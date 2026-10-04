package dev.fedorov.ailife.deploy.inventoryhost;

import dev.fedorov.ailife.agents.inventory.InventoryAgentApplication;
import dev.fedorov.ailife.mcp.inventory.McpInventoryApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3k — Path B (process consolidation), approach B1, applied to the <b>sixth cold
 * host-unit</b>: the <b>Inventory</b> unit.
 *
 * <p>Boots the Inventory domain's contexts — {@code inventory-agent} (the LLM specialist that photographs
 * things into containers and renders/decodes their QR labels) and {@code mcp-inventory} (its domain-MCP over
 * the {@code inventory.*} schema) — <b>in one JVM</b>, each an independent Spring context on its own port.
 * Nothing in the modules' contracts changes: the agent keeps its {@code /agents/inventory/*} endpoints and
 * the MCP keeps its {@code /internal/*} passthrough + MCP/SSE surface on the same URLs/ports.
 *
 * <p><b>Why this unit exists late.</b> The Inventory domain closed 2026-09-27, after the topology map was
 * drawn (2026-08-28), so it was the one domain with no host assignment at all. It takes the <b>Docs shape</b>
 * (3f) exactly: a single agent plus its own domain-MCP, which also binds the <i>hot</i>
 * {@code mcp-media-processing} (the vision caption) without co-hosting it.
 *
 * <p><b>What makes a cold host different from the resident tier:</b> resident hosts keep the agent tier and
 * the MCP tier in separate JVMs (topology-map §Grouping 2 — different resource profiles, both
 * always-in-memory). Cold hosts instead group by <b>co-usage affinity</b> (§Grouping 1): "где что лежит" is
 * an occasional ask, so the whole unit is started/stopped together and co-hosting reclaims the ~300&nbsp;MB
 * per-process JVM baseline for the unit at once.
 *
 * <p>Two shared-classpath concerns are handled by the launcher:
 * <ul>
 *   <li><b>{@code application.yml}</b> (both at {@code classpath:/application.yml}) — each context is
 *       booted with a unique, non-existent {@code spring.config.name} that skips it; the per-module
 *       {@code @ConfigurationProperties} self-default to the compose hostnames/ports, and the deploy env
 *       supplies the rest;</li>
 *   <li><b>web type</b> — the config-name skip also drops each module's own
 *       {@code spring.main.web-application-type}, so the launcher re-supplies it
 *       ({@code inventory-agent} is reactive; {@code mcp-inventory} carries both {@code spring-web} and
 *       {@code webflux}, so it is pinned servlet rather than left to an ambiguous auto-detect).</li>
 * </ul>
 * The single agent also needs its {@code agent.skills-classpath} re-supplied: it lives in the skipped
 * {@code application.yml} and the runtime <b>fails startup</b> if {@code AGENT.md} declares skills the
 * (then empty) registry never loaded. Only one {@code AGENT.md} is on this host's classpath
 * ({@code inventory-agent}'s; {@code mcp-inventory} has none), so the resident tier's per-agent
 * manifest-path fix (3d) is not needed here — the default {@code classpath:/AGENT.md} is unambiguous.
 *
 * <p>Consuming these modules is possible because #584 slice 3a made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier); both Inventory modules were
 * built after that enabler, so they already carried it.
 *
 * <p><b>Slice status (3k):</b> {@link #COLD_INVENTORY} is the Inventory cold host-unit (topology-map.md
 * §Cold). Co-residency is proven by {@code InventoryHostFootprintIntegrationTest}. Wiring each context's
 * real config from the deploy environment (so {@link #main} is a drop-in for the separate service
 * processes), the agent→MCP SSE binding, and the on-demand start/stop lifecycle land at deploy (Mac;
 * ADR-0006 slices 1/3 + LC-2).
 */
public final class InventoryHost {

    private InventoryHost() {}

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
     * The Inventory cold host-unit — the agent + its domain-MCP (topology-map.md §Cold: inventory-agent +
     * mcp-inventory; "reads the hot mcp-media-processing"). The only cold unit still without a launcher is
     * Coach (parked #289) — same mechanism when the epic thaws (ADR-0006 item 4).
     */
    public static final List<Hosted> COLD_INVENTORY = List.of(
            new Hosted(McpInventoryApplication.class, "mcp-inventory", "servlet", null),
            new Hosted(InventoryAgentApplication.class, "inventory-agent", "reactive",
                    "classpath*:skills/inventory/*/SKILL.md"));

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
        // + the agent→co-hosted-MCP SSE binding land with the deployable-host rollout; the co-residency
        // mechanism is proven by the IT.
        for (Hosted h : COLD_INVENTORY) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
