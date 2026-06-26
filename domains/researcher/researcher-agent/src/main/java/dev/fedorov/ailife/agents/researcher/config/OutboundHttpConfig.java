package dev.fedorov.ailife.agents.researcher.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The agent-specific outbound {@link WebClient}, {@code clone()}d off the shared builder to avoid
 * base-URL leakage (same pattern as the other agents): {@code mcpWeb} backs the research flow's calls
 * to the capability (R-d). The shared {@code profile/notifier/memory} WebClients the runtime clients
 * bind on live in {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from
 * {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, ResearcherAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }
}
