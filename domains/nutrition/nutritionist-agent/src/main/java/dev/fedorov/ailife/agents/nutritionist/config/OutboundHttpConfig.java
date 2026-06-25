package dev.fedorov.ailife.agents.nutritionist.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per outbound dependency, each {@code clone()}d off the shared builder to
 * avoid base-URL leakage (same pattern as the other agents). The {@code profile/notifier/memory}
 * beans are picked up by qualifier by the shared {@code agent-runtime} clients. The
 * {@code mcpNutrition/mcpMediaProcessing/mcpWeb} beans back the deterministic capability calls the
 * flows make (NU-c onward).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpNutritionWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpNutritionUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    @Bean
    public WebClient mcpFoodDataWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpFoodDataUrl()).build();
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "nutritionist");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                     NutritionistAgentProperties props) {
        // Default editorial theme → the convenience ctor builds the renderer (no per-agent RenderConfig).
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }

    @Bean
    public WebClient orchestratorWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getOrchestratorUrl()).build();
    }

    @Bean
    public OrchestratorInvokeClient orchestratorInvokeClient(
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient) {
        return new OrchestratorInvokeClient(orchestratorWebClient);
    }

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient memoryServiceWebClient(WebClient.Builder builder, NutritionistAgentProperties props) {
        return builder.clone().baseUrl(props.getMemoryServiceUrl()).build();
    }
}
