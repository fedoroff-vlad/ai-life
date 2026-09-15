package dev.fedorov.ailife.deploy.financeauxhost;

import dev.fedorov.ailife.mcp.chartrender.McpChartRenderApplication;
import dev.fedorov.ailife.mcp.icsimport.McpIcsImportApplication;
import dev.fedorov.ailife.mcp.marketdata.McpMarketDataApplication;
import dev.fedorov.ailife.mcp.moneyproimport.McpMoneyProImportApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3j — Path B (process consolidation), approach B1, applied to the <b>fifth cold
 * host-unit</b>: the <b>Finance-aux</b> unit.
 *
 * <p>Boots the Finance-aux cluster's contexts — two schema-less capability-MCPs ({@code mcp-market-data}
 * quotes, {@code mcp-chart-render} PNG rendering) and two one-shot/periodic import domain-MCPs
 * ({@code mcp-money-pro-import} Money&nbsp;Pro CSV history, {@code mcp-ics-import} read-only ICS feeds) —
 * <b>in one JVM</b>, each an independent Spring context on its own port. Nothing in the modules' contracts
 * changes: each MCP keeps its {@code /internal/*} passthrough + MCP/SSE surface on the same URLs/ports.
 *
 * <p><b>What makes this cold unit distinct:</b> unlike the earlier cold units (Docs 3f, Content 3g,
 * Lifestyle 3h, Brief+Travel 3i) it contains <b>no agent</b> — it is an MCP-only cluster, grouped by
 * co-usage affinity (topology-map §Grouping 1): these four aux MCPs are all seldom-used finance/calendar
 * helpers that can be started/stopped together. So it needs neither the per-agent manifest-path fix (#584
 * 3d) nor the {@code agent.skills-classpath} re-supply — like the resident Domain-MCP-hot host (3c) it only
 * has to skip the shared {@code application.yml} and pin the web type.
 *
 * <p>Two shared-classpath concerns are handled by the launcher (as in the other consolidation hosts):
 * <ul>
 *   <li><b>{@code application.yml}</b> (all at {@code classpath:/application.yml}) — each context is booted
 *       with a unique, non-existent {@code spring.config.name} that skips it; the per-module
 *       {@code @ConfigurationProperties} self-default to the compose hostnames/ports, and the deploy env
 *       supplies the rest;</li>
 *   <li><b>web type</b> — the config-name skip also drops each module's own
 *       {@code spring.main.web-application-type}, so the launcher re-supplies it. {@code mcp-market-data}
 *       and {@code mcp-chart-render} are webflux-only → {@code reactive}; {@code mcp-money-pro-import} and
 *       {@code mcp-ics-import} carry both {@code spring-web} and {@code webflux} (like {@code mcp-creator}),
 *       so they are pinned {@code servlet} rather than left to an ambiguous auto-detect.</li>
 * </ul>
 *
 * <p>Consuming these modules is possible because #584 slice 3j made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3j):</b> {@link #COLD_FINANCE_AUX} is the Finance-aux cold host-unit
 * (topology-map.md §Cold). Co-residency is proven by {@code FinanceAuxHostFootprintIT}. Wiring each
 * context's real config from the deploy environment (so {@link #main} is a drop-in for the separate service
 * processes) + the on-demand start/stop lifecycle land at deploy (Mac; ADR-0006 slices 1/3 + LC-2). Only
 * the parked <b>Coach</b> unit (#289) remains after this.
 */
public final class FinanceAuxHost {

    private FinanceAuxHost() {}

    /**
     * One co-hosted module: its {@code @SpringBootApplication} class, a short {@code name} (drives the
     * config-name skip {@code host-<name>}), and its {@code webType} ({@code reactive}/{@code servlet}).
     * No agent lives on this host, so — unlike {@code ContentHost} — there is no skills-glob field.
     */
    public record Hosted(Class<?> app, String name, String webType) {
        String configName() { return "host-" + name; }
    }

    /**
     * The Finance-aux cold host-unit — the two aux capability-MCPs + the two import domain-MCPs
     * (topology-map.md §Cold: mcp-market-data · mcp-chart-render · mcp-money-pro-import · mcp-ics-import).
     * The only cold unit left after this is the parked Coach unit (#289; ADR-0006 item 4).
     */
    public static final List<Hosted> COLD_FINANCE_AUX = List.of(
            new Hosted(McpMarketDataApplication.class, "mcp-market-data", "reactive"),
            new Hosted(McpChartRenderApplication.class, "mcp-chart-render", "reactive"),
            new Hosted(McpMoneyProImportApplication.class, "mcp-money-pro-import", "servlet"),
            new Hosted(McpIcsImportApplication.class, "mcp-ics-import", "servlet"));

    /**
     * The properties that are structural (identical in test and deploy): skip the module's
     * {@code application.yml} and pin the web type. Datasource, ports, external URLs and the MCP-server
     * identity come from the caller / env.
     */
    public static Map<String, Object> structuralProps(Hosted hosted) {
        Map<String, Object> p = new HashMap<>();
        // unique + non-existent → the module's classpath application.yml is skipped, so co-hosted
        // modules never fight over classpath:/application.yml.
        p.put("spring.config.name", hosted.configName());
        // re-supplied because the config-name skip dropped the module's own web-type declaration
        p.put("spring.main.web-application-type", hosted.webType());
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
        // lands with the deployable-host rollout; the co-residency mechanism is proven by the IT.
        for (Hosted h : COLD_FINANCE_AUX) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
