package dev.fedorov.ailife.agents.tasks.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The qualified {@link WebClient} beans {@code AgentRuntimeConfig}'s shared clients bind on.
 * Each is {@code clone()}d off Spring Boot's shared builder (which mutates state on
 * {@code baseUrl}) — same pattern calendar-agent / finance-agent use. The skeleton wires only
 * the three the runtime requires; the mcp-tasks client is added with the first skill.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient profileServiceWebClient(WebClient.Builder builder, TasksAgentProperties props) {
        return builder.clone().baseUrl(props.getProfileServiceUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(WebClient.Builder builder, TasksAgentProperties props) {
        return builder.clone().baseUrl(props.getNotifierUrl()).build();
    }

    @Bean
    public WebClient memoryServiceWebClient(WebClient.Builder builder, TasksAgentProperties props) {
        return builder.clone().baseUrl(props.getMemoryServiceUrl()).build();
    }
}
