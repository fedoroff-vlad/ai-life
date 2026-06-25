package dev.fedorov.ailife.agents.chef.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per outbound dependency, each {@code clone()}d off the shared builder to
 * avoid base-URL leakage (same pattern as the other agents). The {@code profile/notifier/memory}
 * beans are picked up by qualifier by the shared {@code agent-runtime} clients. The
 * {@code mcpNutrition/mcpWeb} beans back the deterministic capability calls the recipe flow makes
 * (CH-b onward).
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

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, ChefAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, ChefAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient memoryServiceWebClient(WebClient.Builder builder, ChefAgentProperties props) {
        return builder.clone().baseUrl(props.getMemoryServiceUrl()).build();
    }
}
