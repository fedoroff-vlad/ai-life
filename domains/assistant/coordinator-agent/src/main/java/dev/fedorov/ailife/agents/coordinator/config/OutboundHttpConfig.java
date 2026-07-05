package dev.fedorov.ailife.agents.coordinator.config;

import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The coordinator's only agent-specific outbound wiring (Slice B2): the orchestrator hub it reaches
 * specialists through. Agents never call each other directly — the coordinator invokes a specialist's
 * {@code brief} read-action via {@code POST /v1/agents/invoke} (locked inter-agent path,
 * architecture.md §Decisions). The shared {@code profile/notifier/memory} WebClients live in
 * {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}); we
 * {@code clone()} the shared builder per base URL, same pattern calendar-agent uses.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient orchestratorWebClient(WebClient.Builder builder, CoordinatorAgentProperties props) {
        return builder.clone().baseUrl(props.getOrchestratorUrl()).build();
    }

    @Bean
    public OrchestratorInvokeClient orchestratorInvokeClient(
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient) {
        return new OrchestratorInvokeClient(orchestratorWebClient);
    }
}
