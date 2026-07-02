package dev.fedorov.ailife.mcp.briefing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP clients. Currently only scheduler-service (BR-f2); lift to a
 * per-dependency pattern (one bean per URL with {@code .clone()}) when a second
 * outbound destination lands. Mirrors mcp-finance's {@code HttpConfig}.
 */
@Configuration
public class HttpConfig {

    @Bean
    public WebClient schedulerWebClient(WebClient.Builder builder, McpBriefingProperties props) {
        return builder.clone().baseUrl(props.getSchedulerUrl()).build();
    }
}
