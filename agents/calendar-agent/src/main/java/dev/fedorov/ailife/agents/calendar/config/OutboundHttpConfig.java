package dev.fedorov.ailife.agents.calendar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per outbound dependency, each with its own base URL.
 * Spring Boot's shared {@code WebClient.Builder} mutates state when you set
 * {@code baseUrl}, so we {@code clone()} per use — same pattern the orchestrator
 * uses to dial agent endpoints.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, CalendarAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, CalendarAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient icsImportWebClient(WebClient.Builder builder, CalendarAgentProperties props) {
        return builder.clone().baseUrl(props.getIcsImportUrl()).build();
    }
}
