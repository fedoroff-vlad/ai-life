package dev.fedorov.ailife.agents.travel.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.ChartRenderClient;
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
 * builder to avoid base-URL leakage (same pattern as the other agents): {@code mcpTravel} (its data) +
 * {@code mcpWeather} (geocoding + the TR-d climate season substrate) + {@code mcpWeb} (TR-d
 * destination research) + {@code mcpTravelSearch} (TR-f2 live flight/hotel options over the shared
 * mcp-travel-search capability) + {@code orchestrator} (the hub the TR-d planner reaches to invoke the finance
 * and calendar {@code brief} actions — same wiring as coordinator-agent) + the TR-e deliverable seam:
 * {@code mediaService} (blob store) + {@code mcpChartRender} (the climate-by-month curve), wired into a
 * {@link MediaStoreClient} + {@link DeliverablePublisher} exactly as the finance report board does. The
 * shared {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s
 * {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpTravelWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpTravelUrl()).build();
    }

    @Bean
    public WebClient mcpWeatherWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWeatherUrl()).build();
    }

    /**
     * The shared personalization read-resolution engine (ADR-0005): the trip/packing flows resolve the
     * requester's travel profile through {@link ProfileScopeResolver} (self → own household-default →
     * family/shared household-default → empty, #490 FO-3), reusing {@link ProfileSharingClient}'s one
     * identity read over the shared {@code profileServiceWebClient} (from {@code AgentRuntimeConfig}).
     * travel is not a sharing-write domain, so it wires its own {@code ProfileSharingClient} here.
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

    /** Shared {@code mcp-weather} geocode client (agent-runtime) over this agent's weather WebClient. */
    @Bean
    public GeocodeClient geocodeClient(
            @Qualifier("mcpWeatherWebClient") WebClient mcpWeatherWebClient) {
        return new GeocodeClient(mcpWeatherWebClient);
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    /** Shared {@code mcp-web} search client (agent-runtime) over this agent's web WebClient. */
    @Bean
    public WebSearchClient webSearchClient(@Qualifier("mcpWebWebClient") WebClient mcpWebWebClient) {
        return new WebSearchClient(mcpWebWebClient);
    }

    @Bean
    public WebClient mcpTravelSearchWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpTravelSearchUrl()).build();
    }

    @Bean
    public WebClient orchestratorWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getOrchestratorUrl()).build();
    }

    @Bean
    public OrchestratorInvokeClient orchestratorInvokeClient(
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient) {
        return new OrchestratorInvokeClient(orchestratorWebClient);
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "travel");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                     TravelAgentProperties props) {
        // Default editorial theme → the convenience ctor builds the renderer (no per-agent RenderConfig).
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }

    @Bean
    public WebClient mcpChartRenderWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpChartRenderUrl()).build();
    }

    /** Shared {@code mcp-chart-render} client (agent-runtime) over this agent's chart-render WebClient. */
    @Bean
    public ChartRenderClient chartRenderClient(
            @Qualifier("mcpChartRenderWebClient") WebClient mcpChartRenderWebClient) {
        return new ChartRenderClient(mcpChartRenderWebClient);
    }
}
