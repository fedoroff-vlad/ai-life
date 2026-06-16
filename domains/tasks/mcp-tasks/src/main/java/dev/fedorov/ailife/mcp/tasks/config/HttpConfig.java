package dev.fedorov.ailife.mcp.tasks.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP clients. Currently only scheduler-service (the {@code weekly.review}
 * cron registration); lift to a per-dependency {@code .clone()} pattern when a
 * second outbound destination lands. Mirrors mcp-finance's {@code HttpConfig}.
 */
@Configuration
public class HttpConfig {

    @Bean
    public WebClient schedulerWebClient(WebClient.Builder builder, McpTasksProperties props) {
        return builder.clone().baseUrl(props.getSchedulerUrl()).build();
    }
}
