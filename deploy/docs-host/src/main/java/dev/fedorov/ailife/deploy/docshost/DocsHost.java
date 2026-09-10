package dev.fedorov.ailife.deploy.docshost;

import dev.fedorov.ailife.agents.docs.DocsAgentApplication;
import dev.fedorov.ailife.mcp.docs.McpDocsApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3f — Path B (process consolidation), approach B1, applied to the <b>first cold
 * host-unit</b>: the <b>Docs</b> unit.
 *
 * <p>Boots the Docs domain's contexts — {@code docs-agent} (the LLM specialist) and {@code mcp-docs} (its
 * domain-MCP) — <b>in one JVM</b>, each an independent Spring context on its own port. Nothing in the
 * modules' contracts changes: the agent keeps its {@code /agents/docs/*} endpoints and the MCP keeps its
 * {@code /internal/documents} passthrough + MCP/SSE surface on the same URLs/ports.
 *
 * <p><b>What makes a cold host different from the resident tier:</b> resident hosts keep the agent tier
 * and the MCP tier in separate JVMs (topology-map §Grouping 2 — they have different resource profiles and
 * are both always-in-memory). Cold hosts instead group by <b>co-usage affinity</b> (§Grouping 1): a whole
 * cold cluster (here just agent + its MCP) is started/stopped together, so co-hosting them reclaims the
 * ~300&nbsp;MB per-process JVM baseline for the entire unit at once. This is the pilot that proves an
 * agent and a domain-MCP can share one JVM.
 *
 * <p>Two shared-classpath concerns are handled by the launcher:
 * <ul>
 *   <li><b>{@code application.yml}</b> (both at {@code classpath:/application.yml}) — each context is
 *       booted with a unique, non-existent {@code spring.config.name} that skips it; the per-module
 *       {@code @ConfigurationProperties} self-default to the compose hostnames/ports, and the deploy env
 *       supplies the rest;</li>
 *   <li><b>web type</b> — the config-name skip also drops each module's own
 *       {@code spring.main.web-application-type}, so the launcher re-supplies it ({@code docs-agent} is
 *       reactive; {@code mcp-docs} carries both {@code spring-web} and {@code webflux}, so it is pinned
 *       servlet rather than left to an ambiguous auto-detect).</li>
 * </ul>
 * The single agent also needs its {@code agent.skills-classpath} re-supplied: it lives in the skipped
 * {@code application.yml} and the runtime <b>fails startup</b> if {@code AGENT.md} declares skills the
 * (then empty) registry never loaded. Only one {@code AGENT.md} is on this host's classpath
 * ({@code docs-agent}'s; {@code mcp-docs} has none), so the resident tier's per-agent manifest-path fix is
 * not needed here — the default {@code classpath:/AGENT.md} is unambiguous.
 *
 * <p>Consuming these modules is possible because #584 slice 3f made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3f):</b> {@link #COLD_DOCS} is the Docs cold host-unit (topology-map.md §Cold).
 * Co-residency is proven by {@code DocsHostFootprintIT}. Wiring each context's real config from the
 * deploy environment (so {@link #main} is a drop-in for the separate service processes), the agent→MCP
 * SSE binding, and the on-demand start/stop lifecycle land at deploy (Mac; ADR-0006 slices 1/3 + LC-2).
 */
public final class DocsHost {

    private DocsHost() {}

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
     * The Docs cold host-unit — the agent + its domain-MCP (topology-map.md §Cold: docs-agent + mcp-docs;
     * "reads the hot mcp-media-processing"). Other cold units (Content, Lifestyle, Brief+Travel,
     * Finance-aux, Coach) each get their own launcher module as consolidation rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> COLD_DOCS = List.of(
            new Hosted(McpDocsApplication.class, "mcp-docs", "servlet", null),
            new Hosted(DocsAgentApplication.class, "docs-agent", "reactive",
                    "classpath*:skills/docs/*/SKILL.md"));

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
        for (Hosted h : COLD_DOCS) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
