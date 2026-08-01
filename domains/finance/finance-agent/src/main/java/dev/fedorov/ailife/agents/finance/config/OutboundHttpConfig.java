package dev.fedorov.ailife.agents.finance.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each with its own base URL.
 * Spring Boot's shared {@code WebClient.Builder} mutates state when you set
 * {@code baseUrl}, so we {@code clone()} per use — same pattern calendar-agent
 * and the orchestrator already use. The shared {@code profile/notifier/memory} WebClients live in
 * {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpFinanceWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpFinanceUrl()).build();
    }

    @Bean
    public WebClient mediaServiceWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    @Bean
    public MediaStoreClient mediaStoreClient(
            @Qualifier("mediaServiceWebClient") WebClient mediaServiceWebClient) {
        return new MediaStoreClient(mediaServiceWebClient, "finance");
    }

    @Bean
    public DeliverablePublisher deliverablePublisher(MediaStoreClient mediaStoreClient,
                                                     FinanceAgentProperties props) {
        // Default editorial theme → the convenience ctor builds the renderer (no per-agent RenderConfig).
        return new DeliverablePublisher(mediaStoreClient, props.getPublicMediaBaseUrl());
    }

    @Bean
    public WebClient moneyProImportWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMoneyProImportUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    @Bean
    public WebClient mcpMarketDataWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMarketDataUrl()).build();
    }

    @Bean
    public WebClient mcpChartRenderWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpChartRenderUrl()).build();
    }

    /**
     * The sharing capability's identity read (ADR-0002 slice 4), over the shared
     * {@code profileServiceWebClient} (built by agent-runtime from {@code SharedClientProperties}) — the
     * same client backing {@link dev.fedorov.ailife.agentruntime.http.ProfileClient}. Used by the read
     * path ({@code financial-advisor} shared-scope) to union spending across the member's personal ∪
     * shared households; mirrors calendar-agent's wiring.
     */
    @Bean
    public ProfileSharingClient profileSharingClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileSharingClient(profileServiceWebClient);
    }

    /**
     * The sharing capability's <b>write-path</b> engine (ADR-0002 slice 4b), wired with finance's
     * {@code FinanceSharingPolicy} (injected as the {@link DefaultSharingPolicy}). {@code AccountManager}
     * routes a chat-created account against the acting user's household-routing split to a concrete
     * personal/shared {@code household_id}; mirrors calendar-agent's wiring.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy) {
        return new SharingResolver(profileSharingClient, defaultSharingPolicy);
    }
}
