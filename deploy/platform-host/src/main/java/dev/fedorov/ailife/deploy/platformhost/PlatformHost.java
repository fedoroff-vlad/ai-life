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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0006 / #584 slice 3e — Path B (process consolidation), approach B1, applied to the resident
 * <b>Platform-hot</b> tier.
 *
 * <p>Boots several platform service module contexts <b>in one JVM</b>, each an independent Spring context
 * on its own port. Nothing in the modules' contracts changes: each service keeps its {@code /v1/*},
 * {@code /internal/*} and inbound endpoints on the same URL/port. The win is that the ~300&nbsp;MB
 * per-process JVM baseline (JIT/code-cache, metaspace, GC, thread pools) is paid <b>once per host</b>
 * instead of once per module. The two isolated singletons, {@code memory-service} (heavy pgvector + AGE
 * on the recall hot path) and {@code llm-gateway} (holds the model + its own LC-4 downshift lifecycle),
 * are deliberately NOT consolidated (topology-map.md §Grouping principle 3).
 *
 * <p>Each context is started with a unique, non-existent {@code spring.config.name} so the modules'
 * {@code application.yml} resources (all at {@code classpath:/application.yml}) do not collide. Because
 * that skip also drops each module's {@code spring.main.web-application-type} declaration, the launcher
 * <b>re-supplies the web type explicitly</b> per module — this is not optional for the platform tier: a
 * servlet service such as {@code scheduler-service} carries both {@code spring-web} and (test-only)
 * {@code webflux} on its classpath, so auto-detection would be ambiguous. Every other value the module
 * would read from its {@code application.yml} self-defaults on the module's {@code @ConfigurationProperties}
 * (the compose hostnames/ports) or is supplied by the deploy environment.
 *
 * <p>Consuming these modules is possible because #584 slice 3e made each module's main Maven artifact a
 * plain classes jar (the executable moved to the {@code -exec} classifier).
 *
 * <p><b>Slice status (3e):</b> {@link #RESIDENT_HOT} is the always-on Platform-hot set (topology-map.md
 * §Resident). Co-residency is proven by {@code PlatformHostFootprintIT}. Wiring each context's real
 * config from the deploy environment (so {@link #main} is a drop-in for the separate service processes)
 * + the RAM measurement land at deploy (Mac; ADR-0006 slices 1/3).
 */
public final class PlatformHost {

    private PlatformHost() {}

    /**
     * One co-hosted service: its {@code @SpringBootApplication} class, a short {@code name} (drives the
     * config-name skip {@code host-<name>}), and its {@code webType} ({@code reactive} or {@code servlet})
     * — re-supplied because the config-name skip drops the module's own yml declaration.
     */
    public record Hosted(Class<?> app, String name, String webType) {
        String configName() { return "host-" + name; }
    }

    /**
     * The resident Platform-hot set — the always-in-memory platform contexts (topology-map.md §Resident:
     * gateway-telegram · orchestrator · profile-service · notifier-service · scheduler-service ·
     * conversation-service · media-service). The isolated singletons (memory-service, llm-gateway) are
     * never consolidated; cold platform-adjacent hosts group into their cold host-units as consolidation
     * rolls out (ADR-0006 item 4).
     */
    public static final List<Hosted> RESIDENT_HOT = List.of(
            new Hosted(GatewayApplication.class, "gateway-telegram", "reactive"),
            new Hosted(OrchestratorApplication.class, "orchestrator", "reactive"),
            new Hosted(NotifierApplication.class, "notifier-service", "reactive"),
            new Hosted(ProfileServiceApplication.class, "profile-service", "servlet"),
            new Hosted(ConversationServiceApplication.class, "conversation-service", "servlet"),
            new Hosted(MediaServiceApplication.class, "media-service", "servlet"),
            new Hosted(SchedulerApplication.class, "scheduler-service", "servlet"));

    /**
     * The properties that are structural (identical in test and deploy): skip the module's
     * {@code application.yml} and pin the web type. Datasource, ports, external URLs and toggles come
     * from the caller / env.
     */
    public static Map<String, Object> structuralProps(Hosted hosted) {
        Map<String, Object> p = new HashMap<>();
        // unique + non-existent → the module's classpath application.yml is skipped, so co-hosted
        // services never fight over classpath:/application.yml.
        p.put("spring.config.name", hosted.configName());
        // re-supplied because the config-name skip dropped the module's own web-type declaration
        p.put("spring.main.web-application-type", hosted.webType());
        return p;
    }

    /** Boot one service context in the current JVM. {@code extra} adds/overrides datasource, ports, URLs. */
    public static ConfigurableApplicationContext boot(Hosted hosted, Map<String, Object> extra) {
        Map<String, Object> props = structuralProps(hosted);
        props.putAll(extra);
        return new SpringApplicationBuilder(hosted.app()).properties(props).run();
    }

    public static void main(String[] args) {
        // Deploy path: each context reads its own config from the environment (the per-module env vars
        // the standalone services already use), keeping its port. Wiring env → per-context property map
        // lands with the deployable-host rollout; the co-residency mechanism is proven by the IT.
        for (Hosted h : RESIDENT_HOT) {
            new SpringApplicationBuilder(h.app())
                    .properties(structuralProps(h))
                    .run(args);
        }
    }
}
