package dev.fedorov.ailife.agents.stylist.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.docrender.DocRenderer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents). The
 * {@code mcpWardrobe/mcpMediaProcessing/mcpWeb/mcpImageGen} beans back the deterministic capability
 * calls the flows make. The shared {@code profile/notifier/memory} WebClients live in
 * {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpWardrobeWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWardrobeUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    @Bean
    public WebClient mcpImageGenWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpImageGenUrl()).build();
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "stylist");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(DocRenderer docRenderer,
                                                     MediaStoreClient mediaStoreClient,
                                                     StylistAgentProperties props) {
        return new DeliverablePublisher(docRenderer, mediaStoreClient, props.getPublicMediaBaseUrl());
    }
}
