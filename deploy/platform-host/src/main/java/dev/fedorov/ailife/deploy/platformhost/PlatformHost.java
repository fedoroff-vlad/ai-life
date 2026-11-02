package dev.fedorov.ailife.deploy.platformhost;

import dev.fedorov.ailife.conversation.ConversationServiceApplication;
import dev.fedorov.ailife.media.MediaServiceApplication;
import dev.fedorov.ailife.notifier.NotifierApplication;
import dev.fedorov.ailife.orchestrator.OrchestratorApplication;
import dev.fedorov.ailife.profile.ProfileServiceApplication;
import dev.fedorov.ailife.scheduler.SchedulerApplication;
import dev.fedorov.ailife.tg.GatewayApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3e (co-residency) + 3s (per-module config) — Path B (process consolidation),
 * approach B1, applied to the resident <b>Platform-hot</b> tier.
 *
 * <p>Boots several platform service module contexts <b>in one JVM</b>, each an independent Spring context
 * on its own port. Nothing in the modules' contracts changes: each service keeps its {@code /v1/*},
 * {@code /internal/*} and inbound endpoints on the same URL/port. The win is that the ~300&nbsp;MB
 * per-process JVM baseline (JIT/code-cache, metaspace, GC, thread pools) is paid <b>once per host</b>
 * instead of once per module. The two isolated singletons, {@code memory-service} (heavy pgvector + AGE
 * on the recall hot path) and {@code llm-gateway} (holds the model + its own LC-4 downshift lifecycle),
 * are deliberately NOT consolidated (topology-map.md §Grouping principle 3).
 *
 * <p><b>How the config collision is solved (slice 3s).</b> Every module's configuration sits at
 * {@code classpath:/application.yml}, so on the host's shared classloader seven contexts would fight over
 * one file. Slice 3e sidestepped that with a unique, non-existent {@code spring.config.name}, which made
 * each context ignore <i>all</i> of its own configuration — workable for an IT that passes properties
 * explicitly, but not for a deployable process: the yml is where each service's {@code env → property}
 * mapping lives (its port, its datasource, the outbound {@code PROFILE_SERVICE_URL} /
 * {@code ORCHESTRATOR_URL} hostnames, the inbox and scheduler tuning, the S3 bucket). So instead each
 * module's pom lands a <b>copy</b> of its own {@code application.yml} at {@code config/<name>/} (one
 * source file, two classpath locations — the standalone {@code -exec.jar} still resolves
 * {@code classpath:/application.yml} unchanged), and each context is booted with
 * {@code spring.config.location} pointing there. Same mechanism as slices 3n (Domain-MCP-hot) and 3q
 * (Agent-hot).
 *
 * <p><b>Why the web-type re-supply stays on this tier.</b> On the Agent-hot host slice 3q could delete it,
 * because every agent's own yml declares {@code spring.main.web-application-type}. Here only two of the
 * seven do — {@code gateway-telegram} (reactive) and {@code scheduler-service} (servlet) — so for the
 * other five the launcher is the <b>only</b> source, not a second copy. And it is load-bearing rather than
 * cosmetic: co-hosting merges seven classpaths, {@code spring-boot-starter-web} arrives from four servlet
 * members, and Boot then deduces SERVLET for every context — including the three reactive ones, which
 * would then mount their routes nowhere. That is how the Domain-MCP-hot host shipped a dead MCP surface in
 * slice 3p. {@code PlatformHostConfigLocationTest} pins the two declared values against this list so the
 * launcher cannot drift from a module that does state its own stack.
 *
 * <p>Consuming these modules is possible because #584 slice 3e made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 */
public final class PlatformHost {

    private PlatformHost() {}

    /**
     * Whether a member owns a schema. It decides nothing about the module standalone — it exists because
     * <b>co-hosting merges seven classpaths into one</b>, which hands the schema-less member starters it
     * never had.
     */
    public enum Persistence {
        /** Owns a schema: its yml carries a datasource and JDBC/JPA must auto-configure. */
        SCHEMA_BOUND,
        /** Holds no schema: JPA/DataSource auto-configuration must stay off. */
        SCHEMA_LESS
    }

    /**
     * Auto-configurations a {@link Persistence#SCHEMA_LESS} member must not get.
     *
     * <p>Standalone, {@code orchestrator} has no JDBC and no JPA on its classpath at all — it is a pure
     * router over HTTP. Co-hosted it inherits its six schema-bound siblings' starters, so Boot
     * auto-configures a {@code DataSource} for which its yml holds no URL and the context dies with
     * <i>"Failed to determine a suitable driver class"</i>. That is exactly how the first deploy of the
     * Domain-MCP-hot host failed (slice 3o) while its IT stayed green, because the IT handed a
     * Testcontainers datasource to <b>every</b> context and so could never see it. This host's IT had the
     * same blind spot until 3s: it gave all seven {@code baseProps()}, orchestrator included.
     */
    private static final List<String> NO_PERSISTENCE_AUTOCONFIG = List.of(
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration");

    /**
     * One co-hosted service: its {@code @SpringBootApplication} class, the module name — which is also the
     * per-module classpath folder its {@code application.yml} copy lands in (pom {@code targetPath}) —
     * whether it owns a schema, and its {@code webType} ({@code reactive} or {@code servlet}).
     *
     * <p>No MCP transport split here, unlike {@code DomainMcpHost}: no platform module is an MCP server,
     * so neither transport starter is on this host's classpath. A future member that is one would bring
     * that collision back.
     */
    public record Hosted(Class<?> app, String name, Persistence persistence, String webType) {

        /**
         * Where this service's own {@code application.yml} copy lives on the classpath. Deliberately not
         * {@code optional:} — if a module's pom loses the per-module resource block, the context must fail
         * fast rather than start on a sibling's configuration, which on this tier would mean a service
         * binding someone else's port and dialling someone else's dependencies.
         */
        public String configLocation() {
            return "classpath:/config/" + name + "/";
        }

        /** Auto-configurations this member must not get once its classpath is shared with its siblings. */
        public List<String> excludedAutoConfigurations() {
            return persistence == Persistence.SCHEMA_LESS ? NO_PERSISTENCE_AUTOCONFIG : List.of();
        }
    }

    /**
     * The resident Platform-hot set — the always-in-memory platform contexts (topology-map.md §Resident:
     * gateway-telegram · orchestrator · profile-service · notifier-service · scheduler-service ·
     * conversation-service · media-service). The isolated singletons (memory-service, llm-gateway) are
     * never consolidated; cold platform-adjacent hosts group into their cold host-units as consolidation
     * rolls out (ADR-0006 item 4).
     *
     * <p>{@code orchestrator} is the one schema-less member: it is the only service here whose yml carries
     * no {@code spring.datasource}. {@code PlatformHostConfigLocationTest} checks that classification
     * against the ymls themselves, so adding or removing a datasource forces this list to follow.
     */
    public static final List<Hosted> RESIDENT_HOT = List.of(
            new Hosted(GatewayApplication.class, "gateway-telegram", Persistence.SCHEMA_BOUND, "reactive"),
            new Hosted(OrchestratorApplication.class, "orchestrator", Persistence.SCHEMA_LESS, "reactive"),
            new Hosted(NotifierApplication.class, "notifier-service", Persistence.SCHEMA_BOUND, "reactive"),
            new Hosted(ProfileServiceApplication.class, "profile-service", Persistence.SCHEMA_BOUND, "servlet"),
            new Hosted(ConversationServiceApplication.class, "conversation-service", Persistence.SCHEMA_BOUND, "servlet"),
            new Hosted(MediaServiceApplication.class, "media-service", Persistence.SCHEMA_BOUND, "servlet"),
            new Hosted(SchedulerApplication.class, "scheduler-service", Persistence.SCHEMA_BOUND, "servlet"));

    /**
     * Boot one service context in the current JVM, reading that service's own {@code application.yml}.
     *
     * <p>{@code overrides} are passed as <b>command-line arguments</b>, not as
     * {@link SpringApplicationBuilder#properties}: Boot puts builder properties in the
     * {@code defaultProperties} source, which ranks <i>below</i> config files, so a caller's value would
     * lose to the yml. Command-line args outrank it, which is what a caller overriding (say) the
     * datasource for a test actually means.
     */
    public static ConfigurableApplicationContext boot(Hosted hosted, Map<String, Object> overrides) {
        return new SpringApplicationBuilder(hosted.app())
                .run(launchArgs(hosted, overrides, new String[0]));
    }

    /**
     * The single place a member's launch arguments are assembled, shared by {@link #boot} and
     * {@link #main} on purpose: on the Domain-MCP-hot host two separate wiring paths let the IT pass while
     * the deploy died (slice 3o). Anything a co-hosted context needs belongs here.
     */
    private static String[] launchArgs(Hosted hosted, Map<String, Object> overrides, String[] extra) {
        List<String> args = new ArrayList<>();
        args.add("--spring.config.location=" + hosted.configLocation());
        // The logging system initialises ONCE per JVM, so the first context's spring.application.name
        // would prefix every other context's lines — seven services all logging as [gateway-telegram],
        // which is how the Domain-MCP-hot host's first deploy failure read as a caldav fault when the
        // dead context was mcp-web. Drop the field rather than let six in seven lie; the logger name
        // still carries the module (d.f.ailife.orchestrator...).
        args.add("--logging.include-application-name=false");
        // Each context's web stack. Five of the seven ymls do not declare it, so for them this is the
        // only source; see the class javadoc for why auto-detection cannot be trusted on a merged
        // classpath. For gateway-telegram and scheduler-service, which DO declare it, the test pins
        // these values to the ymls.
        args.add("--spring.main.web-application-type=" + hosted.webType());
        List<String> excluded = hosted.excludedAutoConfigurations();
        if (!excluded.isEmpty()) {
            args.add("--spring.autoconfigure.exclude=" + String.join(",", excluded));
        }
        overrides.forEach((key, value) -> args.add("--" + key + "=" + value));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    /**
     * Deploy entry point: boot every resident service in this JVM. Each reads its own yml, so the
     * per-service env vars the standalone processes already use ({@code GATEWAY_PORT},
     * {@code PROFILE_DB_URL}, {@code SCHEDULER_TICK_MILLIS}, …) keep working untouched and each context
     * keeps the port its module declares. Any extra args are forwarded to every context.
     *
     * <p>{@code run} returns once a context's server is up and its non-daemon threads keep the JVM alive,
     * so the loop starts all seven; each context registers its own Spring shutdown hook.
     */
    public static void main(String[] args) {
        for (Hosted hosted : RESIDENT_HOT) {
            new SpringApplicationBuilder(hosted.app()).run(launchArgs(hosted, Map.of(), args));
        }
    }
}
