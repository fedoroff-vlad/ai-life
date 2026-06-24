package dev.fedorov.ailife.agents.creator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per outbound dependency, each {@code clone()}d off the shared builder to
 * avoid base-URL leakage (same pattern as the other agents). The {@code profile/notifier/memory}
 * beans are picked up by qualifier by the shared {@code agent-runtime} clients; {@code mcpCreator}
 * (its data) + {@code mcpWeb} (the shared web capability) back the deterministic flows (CR-c onward).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpCreatorWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpCreatorUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient memoryServiceWebClient(WebClient.Builder builder, CreatorAgentProperties props) {
        return builder.clone().baseUrl(props.getMemoryServiceUrl()).build();
    }
}
