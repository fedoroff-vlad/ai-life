package dev.fedorov.ailife.deploy.mcphost;

import dev.fedorov.ailife.mcp.caldav.McpCaldavApplication;
import dev.fedorov.ailife.mcp.finance.McpFinanceApplication;
import dev.fedorov.ailife.mcp.mediaprocessing.McpMediaProcessingApplication;
import dev.fedorov.ailife.mcp.tasks.McpTasksApplication;
import dev.fedorov.ailife.mcp.web.McpWebApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3 — Path B (process consolidation), approach B1.
 *
 * <p>Boots several {@code domain-MCP} module contexts <b>in one JVM</b>, each an independent Spring
 * context on its own port with its own MCP server. Nothing in the modules changes: agents still call
 * each MCP's {@code /internal/*} on the same URL/port, and each still exposes its own MCP/SSE surface.
 * The win is that the ~300&nbsp;MB per-process JVM baseline (JIT/code-cache, metaspace, GC, thread
 * pools) is paid <b>once per host</b> instead of once per module.
 *
 * <p>Each context is started with a unique, non-existent {@code spring.config.name} so the modules'
 * {@code application.yml} resources (all at {@code classpath:/application.yml}) do not collide — every
 * value is supplied explicitly by the caller ({@link #boot}) or the deploy environment.
 *
 * <p>Consuming these modules is possible because #584 slice 3a made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3c):</b> {@link #RESIDENT_HOT} is the always-on Domain-MCP-hot set
 * (topology-map.md §Resident). Co-residency is proven by {@code DomainMcpHostFootprintIT}. Wiring each
 * context's real config from the deploy environment (so {@link #main} is a drop-in for the separate
 * service processes) + the RAM measurement land at deploy (Mac; ADR-0006 slices 1/3).
 */
public final class DomainMcpHost {

    private DomainMcpHost() {}

    /** One co-hosted module: its {@code @SpringBootApplication} class + a config-name that skips its yml. */
    public record Hosted(Class<?> app, String configName) {}

    /**
     * The resident Domain-MCP-hot set — the always-in-memory MCP contexts (topology-map.md §Resident:
     * caldav · finance · tasks · web · media-processing). Cold hosts (Content, Lifestyle, Brief+Travel,
     * …) each get their own launcher list as consolidation rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> RESIDENT_HOT = List.of(
            new Hosted(McpCaldavApplication.class, "host-mcp-caldav"),
            new Hosted(McpFinanceApplication.class, "host-mcp-finance"),
            new Hosted(McpTasksApplication.class, "host-mcp-tasks"),
            new Hosted(McpWebApplication.class, "host-mcp-web"),
            new Hosted(McpMediaProcessingApplication.class, "host-mcp-media-processing"));

    /**
     * Boot one module context in the current JVM. {@code props} supplies everything the module would
     * otherwise read from its {@code application.yml} (datasource, port, MCP server name, custom props).
     */
    public static ConfigurableApplicationContext boot(Hosted hosted, Map<String, Object> props) {
        return new SpringApplicationBuilder(hosted.app())
                .properties(props)
                // unique + non-existent → the module's classpath application.yml is skipped, so
                // co-hosted modules never fight over classpath:/application.yml.
                .properties("spring.config.name=" + hosted.configName())
                .run();
    }

    public static void main(String[] args) {
        // Deploy path: each context reads its own config from the environment (the per-module env vars
        // the standalone services already use), keeping its port. Wiring env → per-context property map
        // lands with the deployable-host rollout; the co-residency mechanism is proven by the IT.
        for (Hosted h : RESIDENT_HOT) {
            new SpringApplicationBuilder(h.app())
                    .properties("spring.config.name=" + h.configName())
                    .run(args);
        }
    }
}
