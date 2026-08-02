package dev.fedorov.ailife.agents.tasks.config;

import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The agent-specific {@link WebClient} beans, each {@code clone()}d off Spring Boot's shared builder
 * (which mutates state on {@code baseUrl}) — same pattern calendar-agent / finance-agent use:
 * {@code mcpTasks} (REST passthrough) + {@code orchestrator} (inter-agent invoke). The shared
 * {@code profile/notifier/memory} WebClients {@code AgentRuntimeConfig}'s runtime clients bind on now
 * live in {@code agent-runtime} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    /** REST passthrough to mcp-tasks (e.g. /internal/review) — separate from the MCP-SSE transport. */
    @Bean
    public WebClient mcpTasksWebClient(WebClient.Builder builder, TasksAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpTasksUrl()).build();
    }

    /** To the orchestrator for inter-agent sync calls (/v1/agents/invoke) — the task-to-event chain. */
    @Bean
    public WebClient orchestratorWebClient(WebClient.Builder builder, TasksAgentProperties props) {
        return builder.clone().baseUrl(props.getOrchestratorUrl()).build();
    }

    @Bean
    public OrchestratorInvokeClient orchestratorInvokeClient(
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient) {
        return new OrchestratorInvokeClient(orchestratorWebClient);
    }

    /**
     * The sharing capability's identity read (ADR-0002 slice 5), over the shared
     * {@code profileServiceWebClient} (built by agent-runtime from {@code SharedClientProperties}) — the
     * same client backing {@link dev.fedorov.ailife.agentruntime.http.ProfileClient}. Used by the write
     * path ({@code task-capture}) to route a captured task to the personal or shared household; mirrors
     * calendar-agent / finance-agent wiring.
     */
    @Bean
    public ProfileSharingClient profileSharingClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileSharingClient(profileServiceWebClient);
    }

    /**
     * The sharing capability's <b>write-path</b> engine (ADR-0002 slice 5), wired with tasks'
     * {@code TasksSharingPolicy} (injected as the {@link DefaultSharingPolicy}). {@code TaskCapturer} routes
     * a chat-captured task against the acting user's household-routing split to a concrete personal/shared
     * {@code household_id}; mirrors finance-agent's wiring.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy) {
        return new SharingResolver(profileSharingClient, defaultSharingPolicy);
    }
}
