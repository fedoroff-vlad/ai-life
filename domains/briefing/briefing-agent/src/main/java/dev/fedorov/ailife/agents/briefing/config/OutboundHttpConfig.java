package dev.fedorov.ailife.agents.briefing.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents); {@code mcpBriefing} (its data)
 * + {@code mcpWeather} (weather/geocoding) + {@code mcpWeb} (news) + {@code mcpCaldav} (today's agenda)
 * + {@code mcpFinance} (spend snapshot) back the deterministic profiler/digest flows. The shared
 * {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s {@code AgentRuntimeConfig}
 * (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpBriefingWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpBriefingUrl()).build();
    }

    @Bean
    public WebClient mcpWeatherWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWeatherUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    @Bean
    public WebClient mcpCaldavWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpCaldavUrl()).build();
    }

    @Bean
    public WebClient mcpFinanceWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpFinanceUrl()).build();
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "briefing");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                     BriefingAgentProperties props) {
        // Default editorial theme (the digest board isn't skinned) → the convenience ctor builds the renderer.
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }
}
