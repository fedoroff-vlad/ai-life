package dev.fedorov.ailife.deploy.mcphost;

import dev.fedorov.ailife.mcp.briefing.McpBriefingApplication;
import dev.fedorov.ailife.mcp.travel.McpTravelApplication;
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
 * <p>Consuming the pilot modules is possible because #584 slice 3a made their main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3b):</b> the co-residency mechanism is proven by {@code DomainMcpHostFootprintIT}.
 * Wiring each context's real config from the deploy environment (so {@link #main} is a drop-in for the
 * separate service processes) + the RAM measurement land as consolidation rolls out (ADR-0006 item 4).
 */
public final class DomainMcpHost {

    private DomainMcpHost() {}

    /** One co-hosted module: its {@code @SpringBootApplication} class + a config-name that skips its yml. */
    public record Hosted(Class<?> app, String configName) {}

    /** The pilot set for the slice-3 spike. Extend as consolidation rolls out (ADR-0006 item 4). */
    public static final List<Hosted> PILOT = List.of(
            new Hosted(McpBriefingApplication.class, "host-mcp-briefing"),
            new Hosted(McpTravelApplication.class, "host-mcp-travel"));

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
        for (Hosted h : PILOT) {
            new SpringApplicationBuilder(h.app())
                    .properties("spring.config.name=" + h.configName())
                    .run(args);
        }
    }
}
