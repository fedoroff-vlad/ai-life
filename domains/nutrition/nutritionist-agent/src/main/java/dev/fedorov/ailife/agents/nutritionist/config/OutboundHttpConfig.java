package dev.fedorov.ailife.agents.nutritionist.config;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agentruntime.http.WebSearchClient;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.LearnedSharingPolicy;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import dev.fedorov.ailife.sharing.SharingLearningClient;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents). The
 * {@code mcpNutrition/mcpMediaProcessing/mcpWeb} beans back the deterministic capability calls the
 * flows make (NU-c onward). The shared {@code profile/notifier/memory} WebClients live in
 * {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
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

    /** Shared {@code mcp-web} search client (agent-runtime) over this agent's web WebClient. */
    @Bean
    public WebSearchClient webSearchClient(@Qualifier("mcpWebWebClient") WebClient mcpWebWebClient) {
        return new WebSearchClient(mcpWebWebClient);
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

    /**
     * The sharing capability's identity read (ADR-0002 slice 6), over the shared
     * {@code profileServiceWebClient} (built by agent-runtime from {@code SharedClientProperties}) — the
     * same client backing {@link dev.fedorov.ailife.agentruntime.http.ProfileClient}. Used by the basket
     * write path to route a grocery basket to the member's personal ∪ shared household; mirrors
     * finance-agent's wiring.
     */
    @Bean
    public ProfileSharingClient profileSharingClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileSharingClient(profileServiceWebClient);
    }

    /**
     * The learned-decision tally read/write (ADR-0002 item 8), over the shared {@code memoryServiceWebClient}
     * (built by agent-runtime from {@code SharedClientProperties}). Best-effort — never delays or fails a
     * basket breakdown; mirrors calendar-agent / finance-agent / tasks-agent wiring.
     */
    @Bean
    public SharingLearningClient sharingLearningClient(
            @Qualifier("memoryServiceWebClient") WebClient memoryServiceWebClient) {
        return new SharingLearningClient(memoryServiceWebClient);
    }

    /**
     * The sharing capability's <b>write-path</b> engine (ADR-0002 slice 6), wired with nutrition's
     * {@code NutritionSharingPolicy} (injected as the {@link DefaultSharingPolicy}). {@code BasketBreakdown}
     * routes a saved grocery basket against the acting user's household-routing split to a concrete
     * personal/shared {@code household_id}. Item 8: the static policy is wrapped in a
     * {@link LearnedSharingPolicy} and the resolver uses its learning-enabled constructor, so a basket with
     * no explicit household/personal signal defaults to what the owner has repeatedly chosen for the same
     * signal profile once the tally is deep + decisive, and to the static grocery-basket rule otherwise;
     * explicit choices are recorded. Both best-effort; the routing mechanism is unchanged. Mirrors finance.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy,
                                           SharingLearningClient sharingLearningClient) {
        DefaultSharingPolicy learned =
                new LearnedSharingPolicy(defaultSharingPolicy, sharingLearningClient, "nutrition");
        return new SharingResolver(profileSharingClient, learned, sharingLearningClient, "nutrition");
    }
}
