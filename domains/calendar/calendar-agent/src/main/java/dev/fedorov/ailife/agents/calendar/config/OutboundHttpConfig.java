package dev.fedorov.ailife.agents.calendar.config;

import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
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
 * One {@link WebClient} per agent-specific outbound dependency, each with its own base URL.
 * Spring Boot's shared {@code WebClient.Builder} mutates state when you set
 * {@code baseUrl}, so we {@code clone()} per use — same pattern the orchestrator
 * uses to dial agent endpoints. The shared {@code profile/notifier/memory} WebClients live in
 * {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient icsImportWebClient(WebClient.Builder builder, CalendarAgentProperties props) {
        return builder.clone().baseUrl(props.getIcsImportUrl()).build();
    }

    @Bean
    public WebClient mcpCaldavWebClient(WebClient.Builder builder, CalendarAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpCaldavUrl()).build();
    }

    @Bean
    public WebClient orchestratorWebClient(WebClient.Builder builder, CalendarAgentProperties props) {
        return builder.clone().baseUrl(props.getOrchestratorUrl()).build();
    }

    @Bean
    public OrchestratorInvokeClient orchestratorInvokeClient(
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient) {
        return new OrchestratorInvokeClient(orchestratorWebClient);
    }

    /**
     * The sharing capability's identity read (ADR-0002), over the shared {@code profileServiceWebClient}
     * (built by agent-runtime from {@code SharedClientProperties}) — the same client backing
     * {@link dev.fedorov.ailife.agentruntime.http.ProfileClient}.
     */
    @Bean
    public ProfileSharingClient profileSharingClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileSharingClient(profileServiceWebClient);
    }

    /**
     * The learned-decision tally read/write (ADR-0002 item 8), over the shared {@code memoryServiceWebClient}
     * (built by agent-runtime from {@code SharedClientProperties}) — the same client backing
     * {@link dev.fedorov.ailife.agentruntime.http.MemoryClient}. Best-effort on both sides, so it never
     * delays or fails an event create.
     */
    @Bean
    public SharingLearningClient sharingLearningClient(
            @Qualifier("memoryServiceWebClient") WebClient memoryServiceWebClient) {
        return new SharingLearningClient(memoryServiceWebClient);
    }

    /**
     * The write-path routing engine (ADR-0002). Calendar is the reference domain for the memory-driven
     * default (item 8): its {@code CalendarSharingPolicy} (injected as the {@link DefaultSharingPolicy}) is
     * wrapped in a {@link LearnedSharingPolicy}, so an unscoped event defaults to what the owner has
     * repeatedly chosen for the same signal profile once the tally is deep + decisive, and to the static
     * occasion rule otherwise. The resolver's learning-enabled constructor also records the owner's explicit
     * choices as the learn signal. Both are best-effort; the routing/fallback mechanism is unchanged.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy,
                                           SharingLearningClient sharingLearningClient) {
        DefaultSharingPolicy learned =
                new LearnedSharingPolicy(defaultSharingPolicy, sharingLearningClient, "calendar");
        return new SharingResolver(profileSharingClient, learned, sharingLearningClient, "calendar");
    }
}
