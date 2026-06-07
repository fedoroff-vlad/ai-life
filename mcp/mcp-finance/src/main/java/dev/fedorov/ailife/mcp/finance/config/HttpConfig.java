package dev.fedorov.ailife.mcp.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP clients. Currently only scheduler-service; lift to a per-
 * dependency pattern (one bean per URL with {@code .clone()}) when a second
 * outbound destination lands. Same builder-leakage caveat captured in
 * calendar-agent's {@code OutboundHttpConfig}.
 */
@Configuration
public class HttpConfig {

    @Bean
    public WebClient schedulerWebClient(WebClient.Builder builder, McpFinanceProperties props) {
        return builder.clone().baseUrl(props.getSchedulerUrl()).build();
    }
}
