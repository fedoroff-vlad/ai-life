package dev.fedorov.ailife.agents.stylist.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per outbound dependency, each {@code clone()}d off the shared builder to
 * avoid base-URL leakage (same pattern as the other agents). The {@code profile/notifier/memory}
 * beans are picked up by qualifier by the shared {@code agent-runtime} clients. The
 * {@code mcpWardrobe/mcpMediaProcessing/mcpWeb} beans back the deterministic capability calls the
 * flows add in ST-c..e.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpWardrobeWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWardrobeUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient memoryServiceWebClient(WebClient.Builder builder, StylistAgentProperties props) {
        return builder.clone().baseUrl(props.getMemoryServiceUrl()).build();
    }
}
