package dev.fedorov.ailife.agents.docs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents): {@code mcpDocs} (its data —
 * store + search) + {@code mcpMediaProcessing} (OCR of a document photo). The shared
 * {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s {@code AgentRuntimeConfig}
 * (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpDocsWebClient(WebClient.Builder builder, DocsAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpDocsUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, DocsAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }
}
