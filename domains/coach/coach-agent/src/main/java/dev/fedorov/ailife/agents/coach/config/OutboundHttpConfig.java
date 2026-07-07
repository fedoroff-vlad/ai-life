package dev.fedorov.ailife.agents.coach.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP clients specific to the coach agent (the shared profile / notifier / memory-service
 * clients come from {@code agent-runtime}). Only mcp-coach here — the durable coaching record, reached
 * via {@code /internal/coach/*}. The bean {@code .clone()}s the shared builder and pins its own base
 * URL to avoid base-URL leakage.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpCoachWebClient(WebClient.Builder builder, CoachAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpCoachUrl()).build();
    }
}
