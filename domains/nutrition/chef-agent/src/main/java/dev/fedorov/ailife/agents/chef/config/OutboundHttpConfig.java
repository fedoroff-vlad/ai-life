package dev.fedorov.ailife.agents.chef.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agentruntime.http.WebSearchClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents). The
 * {@code mcpNutrition/mcpWeb} beans back the deterministic capability calls the recipe flow makes
 * (CH-b onward). The shared {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s
 * {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpNutritionWebClient(WebClient.Builder builder, ChefAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpNutritionUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, ChefAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    /** Shared {@code mcp-web} search client (agent-runtime) over this agent's web WebClient. */
    @Bean
    public WebSearchClient webSearchClient(@Qualifier("mcpWebWebClient") WebClient mcpWebWebClient) {
        return new WebSearchClient(mcpWebWebClient);
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, ChefAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "chef");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                     ChefAgentProperties props) {
        // Default editorial theme (chef doesn't skin its cards) → the convenience ctor builds the renderer.
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }
}
