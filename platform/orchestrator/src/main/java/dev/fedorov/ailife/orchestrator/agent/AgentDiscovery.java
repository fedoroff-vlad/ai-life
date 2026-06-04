package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Startup scrape of remote agents. For each {@link AgentRegistryProperties.Registration}
 * the orchestrator GETs {@code <baseUrl>/agents/<name>/manifest}. Successful
 * manifests become the source of truth for intent classification; failures are
 * logged and the agent is skipped (intent routing falls back to echo for that
 * domain until the agent comes online and the orchestrator restarts).
 *
 * <p>For PR9c1 the scrape is one-shot at startup. Future work: periodic refresh /
 * SIGHUP reload — not needed now since agents are pinned in config.
 */
@Configuration
public class AgentDiscovery {

    private static final Logger log = LoggerFactory.getLogger(AgentDiscovery.class);
    private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public AgentRegistry agentRegistry(AgentRegistryProperties props, WebClient.Builder builder) {
        Map<String, AgentManifest> out = new LinkedHashMap<>();
        for (var reg : props.getAgents()) {
            try {
                AgentManifest m = freshClient(builder, reg.getBaseUrl())
                        .get()
                        .uri("/agents/" + reg.getName() + "/manifest")
                        .retrieve()
                        .bodyToMono(AgentManifest.class)
                        .block(MANIFEST_TIMEOUT);
                if (m != null) {
                    out.put(reg.getName(), m);
                    log.info("discovered agent {}: {} intents, {} triggers",
                            reg.getName(),
                            m.intents() == null ? 0 : m.intents().size(),
                            m.triggers() == null ? 0 : m.triggers().size());
                }
            } catch (RuntimeException e) {
                log.warn("manifest fetch failed for agent={} url={}: {}",
                        reg.getName(), reg.getBaseUrl(), e.toString());
            }
        }
        return new AgentRegistry(out);
    }

    /**
     * Spring Boot's {@code WebClient.Builder} is shared (singleton-ish) and most
     * of its setters mutate-and-return-this. Cloning before applying a
     * {@code baseUrl} keeps each remote-agent client independent.
     */
    private static WebClient freshClient(WebClient.Builder shared, String baseUrl) {
        return shared.clone().baseUrl(baseUrl).build();
    }

    /**
     * Routing map for {@link dev.fedorov.ailife.orchestrator.routing.IntentRouter}:
     * keys are agent names, values are {@link Agent}s the router can dispatch to.
     * Echo is always present (built-in fallback); each remote agent whose manifest
     * fetched successfully gets a {@link RemoteAgent}.
     */
    @Bean
    public Map<String, Agent> agentDispatch(EchoAgent echo,
                                            AgentRegistry registry,
                                            AgentRegistryProperties props,
                                            WebClient.Builder builder) {
        Map<String, Agent> out = new LinkedHashMap<>();
        out.put(echo.id(), echo);
        for (var reg : props.getAgents()) {
            if (registry.manifests().containsKey(reg.getName())) {
                out.put(reg.getName(),
                        new RemoteAgent(reg.getName(), freshClient(builder, reg.getBaseUrl())));
            }
        }
        return Map.copyOf(out);
    }
}
