package dev.fedorov.ailife.deploy.mcphost;

import dev.fedorov.ailife.mcp.caldav.McpCaldavApplication;
import dev.fedorov.ailife.mcp.finance.McpFinanceApplication;
import dev.fedorov.ailife.mcp.mediaprocessing.McpMediaProcessingApplication;
import dev.fedorov.ailife.mcp.tasks.McpTasksApplication;
import dev.fedorov.ailife.mcp.web.McpWebApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
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
 * <p><b>How the config collision is solved (slice 3n).</b> Every module's configuration sits at
 * {@code classpath:/application.yml}, so on the host's shared classloader five contexts would fight over
 * one file. Slices 3b…3k sidestepped that with a unique, non-existent {@code spring.config.name}, which
 * made each context ignore <i>all</i> of its own configuration — workable for an IT that passes
 * properties explicitly, but not for a deployable process: the yml is where each module's
 * {@code env → property} mapping lives (ports, datasource, {@code SCHEDULER_URL}, the OCR/STT engine
 * picks …) and where {@code spring.ai.mcp.server.instructions} lives — the tool-surface prose the MCP
 * server hands its clients. So instead each module's pom lands a <b>copy</b> of its own
 * {@code application.yml} at {@code config/<module>/} (one source file, two classpath locations — the
 * standalone {@code -exec.jar} still resolves {@code classpath:/application.yml} unchanged), and each
 * context is booted with {@code spring.config.location} pointing there. Same precedent as slice 3d's
 * per-agent {@code manifest/<name>/AGENT.md}.
 *
 * <p>Consuming these modules is possible because #584 slice 3a made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 */
public final class DomainMcpHost {

    private DomainMcpHost() {}

    /**
     * One co-hosted module: its {@code @SpringBootApplication} class + the module name, which is also
     * the per-module classpath folder its {@code application.yml} copy lands in (pom {@code targetPath}).
     */
    public record Hosted(Class<?> app, String module) {

        /**
         * Where this module's own {@code application.yml} copy lives on the classpath. Deliberately not
         * {@code optional:} — if a module's pom loses the per-module resource block, the context must
         * fail fast rather than start on a sibling's configuration.
         */
        public String configLocation() {
            return "classpath:/config/" + module + "/";
        }
    }

    /**
     * The resident Domain-MCP-hot set — the always-in-memory MCP contexts (topology-map.md §Resident:
     * caldav · finance · tasks · web · media-processing). Cold hosts (Content, Lifestyle, Brief+Travel,
     * …) each get their own launcher list as consolidation rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> RESIDENT_HOT = List.of(
            new Hosted(McpCaldavApplication.class, "mcp-caldav"),
            new Hosted(McpFinanceApplication.class, "mcp-finance"),
            new Hosted(McpTasksApplication.class, "mcp-tasks"),
            new Hosted(McpWebApplication.class, "mcp-web"),
            new Hosted(McpMediaProcessingApplication.class, "mcp-media-processing"));

    /**
     * Boot one module context in the current JVM, reading that module's own {@code application.yml}.
     *
     * <p>{@code overrides} are passed as <b>command-line arguments</b>, not as
     * {@link SpringApplicationBuilder#properties}: Boot puts builder properties in the
     * {@code defaultProperties} source, which ranks <i>below</i> config files, so a caller's value would
     * lose to the yml. Command-line args outrank it, which is what a caller overriding (say) the
     * datasource for a test actually means.
     */
    public static ConfigurableApplicationContext boot(Hosted hosted, Map<String, Object> overrides) {
        List<String> args = new ArrayList<>();
        args.add("--spring.config.location=" + hosted.configLocation());
        overrides.forEach((key, value) -> args.add("--" + key + "=" + value));
        return new SpringApplicationBuilder(hosted.app()).run(args.toArray(String[]::new));
    }

    /**
     * Deploy entry point: boot every resident context in this JVM. Each reads its own yml, so the
     * per-module env vars the standalone services already use (`MCP_CALDAV_PORT`, `MCP_TASKS_DB_URL`, …)
     * keep working untouched and each context keeps the port its module declares. Any extra args are
     * forwarded to every context.
     *
     * <p>{@code run} returns once a context's server is up and its non-daemon threads keep the JVM
     * alive, so the loop starts all five; each context registers its own Spring shutdown hook.
     */
    public static void main(String[] args) {
        for (Hosted hosted : RESIDENT_HOT) {
            List<String> withLocation = new ArrayList<>();
            withLocation.add("--spring.config.location=" + hosted.configLocation());
            withLocation.addAll(List.of(args));
            new SpringApplicationBuilder(hosted.app()).run(withLocation.toArray(String[]::new));
        }
    }
}
