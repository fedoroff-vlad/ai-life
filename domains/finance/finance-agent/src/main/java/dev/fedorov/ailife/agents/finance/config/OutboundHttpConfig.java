package dev.fedorov.ailife.agents.finance.config;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.ChartRenderClient;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.LearnedSharingPolicy;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import dev.fedorov.ailife.sharing.SharingConfirm;
import dev.fedorov.ailife.sharing.SharingLearningClient;
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

    /** Shared {@code mcp-chart-render} client (agent-runtime) over this agent's chart-render WebClient. */
    @Bean
    public ChartRenderClient chartRenderClient(
            @Qualifier("mcpChartRenderWebClient") WebClient mcpChartRenderWebClient) {
        return new ChartRenderClient(mcpChartRenderWebClient);
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
     * The learned-decision tally read/write (ADR-0002 item 8), over the shared {@code memoryServiceWebClient}
     * (built by agent-runtime from {@code SharedClientProperties}). Best-effort — never delays or fails an
     * account create; mirrors calendar-agent's wiring.
     */
    @Bean
    public SharingLearningClient sharingLearningClient(
            @Qualifier("memoryServiceWebClient") WebClient memoryServiceWebClient) {
        return new SharingLearningClient(memoryServiceWebClient);
    }

    /**
     * The sharing capability's <b>write-path</b> engine (ADR-0002 slice 4b), wired with finance's
     * {@code FinanceSharingPolicy} (injected as the {@link DefaultSharingPolicy}). {@code AccountManager}
     * routes a chat-created account against the acting user's household-routing split to a concrete
     * personal/shared {@code household_id}. Item 8: the static policy is wrapped in a
     * {@link LearnedSharingPolicy} and the resolver uses its learning-enabled constructor, so an account
     * with no explicit joint/personal signal defaults to what the owner has repeatedly chosen for the same
     * signal profile once the tally is deep + decisive, and to the static joint-account rule otherwise;
     * explicit choices are recorded. Both best-effort; the routing mechanism is unchanged. Mirrors calendar.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy,
                                           SharingLearningClient sharingLearningClient) {
        DefaultSharingPolicy learned =
                new LearnedSharingPolicy(defaultSharingPolicy, sharingLearningClient, "finance");
        return new SharingResolver(profileSharingClient, learned, sharingLearningClient, "finance");
    }

    /**
     * The reusable confirm-on-ambiguity plumbing (ADR-0002 item 8, DS-N): when {@code AccountManager} can't
     * classify an account and the resolver returns {@code NeedsConfirm}, {@link SharingConfirm} builds the
     * "личное или общее?" ask and, on the reply, drives {@code SharingResolver.confirm} + the create finish.
     * Written once in {@code libs/sharing}; mirrors tasks-agent's wiring.
     */
    @Bean
    public SharingConfirm sharingConfirm(SharingResolver sharingResolver, ObjectMapper json) {
        return new SharingConfirm(sharingResolver, json);
    }
}
