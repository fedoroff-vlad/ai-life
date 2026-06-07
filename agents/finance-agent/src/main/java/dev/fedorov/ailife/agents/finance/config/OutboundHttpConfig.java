package dev.fedorov.ailife.agents.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per outbound dependency, each with its own base URL.
 * Spring Boot's shared {@code WebClient.Builder} mutates state when you set
 * {@code baseUrl}, so we {@code clone()} per use — same pattern calendar-agent
 * and the orchestrator already use.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient memoryServiceWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMemoryServiceUrl()).build();
    }

    @Bean
    public WebClient mcpFinanceWebClient(WebClient.Builder builder, FinanceAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpFinanceUrl()).build();
    }
}
