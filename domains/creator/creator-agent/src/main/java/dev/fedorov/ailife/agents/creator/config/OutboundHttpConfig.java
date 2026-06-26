package dev.fedorov.ailife.agents.creator.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents); {@code mcpCreator}
 * (its data) + {@code mcpWeb} (the shared web capability) back the deterministic flows (CR-c onward).
 * The shared {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s
 * {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpCreatorWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpCreatorUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    @Bean
    public WebClient mcpYoutubeWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpYoutubeUrl()).build();
    }

    @Bean
    public WebClient mcpRedditWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpRedditUrl()).build();
    }

    @Bean
    public WebClient mcpFeedsWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpFeedsUrl()).build();
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "creator");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                     CreatorAgentProperties props) {
        // Default editorial theme → the convenience ctor builds the renderer (no per-agent RenderConfig).
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }
}
