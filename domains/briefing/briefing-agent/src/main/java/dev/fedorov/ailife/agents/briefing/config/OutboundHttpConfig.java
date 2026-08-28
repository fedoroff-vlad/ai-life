package dev.fedorov.ailife.agents.briefing.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agentruntime.http.WebSearchClient;
import dev.fedorov.ailife.profile.ProfileScopeResolver;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents); {@code mcpBriefing} (its data)
 * + {@code mcpWeather} (weather/geocoding) + {@code mcpWeb} (news) + {@code mcpCaldav} (today's agenda)
 * back the deterministic profiler/digest flows; {@code orchestrator} is the hub the digest asks
 * finance-agent's {@code spend_snapshot} action through (no direct mcp-finance read). The shared
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

    /** Shared {@code mcp-weather} geocode client (agent-runtime) over this agent's weather WebClient. */
    @Bean
    public GeocodeClient geocodeClient(
            @Qualifier("mcpWeatherWebClient") WebClient mcpWeatherWebClient) {
        return new GeocodeClient(mcpWeatherWebClient);
    }

    /**
     * The shared personalization read-resolution engine (ADR-0005): the digest's profile lookup
     * (self → own household-default → family/shared household-default → empty, #490 FO-3) runs through
     * {@link ProfileScopeResolver}, reusing {@link ProfileSharingClient}'s one identity read over the
     * shared {@code profileServiceWebClient} (declared in {@code AgentRuntimeConfig}). briefing is not a
     * sharing-write domain, so it wires its own {@code ProfileSharingClient} here (no duplicate bean).
     */
    @Bean
    public ProfileSharingClient profileSharingClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileSharingClient(profileServiceWebClient);
    }

    @Bean
    public ProfileScopeResolver profileScopeResolver(ProfileSharingClient profileSharingClient) {
        return new ProfileScopeResolver(profileSharingClient);
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    /** Shared {@code mcp-web} search client (agent-runtime) — the digest's news gather (8s at the call site). */
    @Bean
    public WebSearchClient webSearchClient(@Qualifier("mcpWebWebClient") WebClient mcpWebWebClient) {
        return new WebSearchClient(mcpWebWebClient);
    }

    @Bean
    public WebClient mcpCaldavWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpCaldavUrl()).build();
    }

    /** The inter-agent hub: the digest asks finance-agent's {@code spend_snapshot} action here. */
    @Bean
    public WebClient orchestratorWebClient(WebClient.Builder builder, BriefingAgentProperties props) {
        return builder.clone().baseUrl(props.getOrchestratorUrl()).build();
    }

    @Bean
    public OrchestratorInvokeClient orchestratorInvokeClient(
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient) {
        return new OrchestratorInvokeClient(orchestratorWebClient);
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
