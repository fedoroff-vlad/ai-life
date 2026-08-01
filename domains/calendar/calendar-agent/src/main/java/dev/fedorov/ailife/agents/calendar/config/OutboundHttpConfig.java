package dev.fedorov.ailife.agents.calendar.config;

import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
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
     * The write-path routing engine, wired with calendar's {@code CalendarSharingPolicy} (injected as the
     * {@link DefaultSharingPolicy}). Replaces the tenant-routing logic formerly inline in the action controller.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy) {
        return new SharingResolver(profileSharingClient, defaultSharingPolicy);
    }
}
