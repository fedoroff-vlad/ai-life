package dev.fedorov.ailife.agents.inventory.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents): {@code mcpInventory} (its
 * data — zones / containers / items) + {@code mcpMediaProcessing} (vision-caption of a photographed
 * thing) + {@code mediaService} (stores the QR label and the container card, IN-d). The shared
 * {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s
 * {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpInventoryWebClient(WebClient.Builder builder, InventoryAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpInventoryUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, InventoryAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    /**
     * The shared capability client for {@code POST /internal/caption} — opt-in per consumer, never a
     * per-agent copy (CLAUDE.md §Conventions). Names a photographed thing so it is findable later.
     */
    @Bean
    public CaptionClient captionClient(
            @Qualifier("mcpMediaProcessingWebClient") WebClient mcpMediaProcessingWebClient) {
        return new CaptionClient(mcpMediaProcessingWebClient);
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, InventoryAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    /** Stores the container's QR label PNG and its rendered card (IN-d). */
    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "inventory");
    }

    /** The shared render → store → link seam for the container card; default editorial theme. */
    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                    InventoryAgentProperties props) {
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }
}
