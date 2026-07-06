package dev.fedorov.ailife.mcp.chartrender.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Uploads rendered charts to media-service ({@code POST /v1/media}). */
    @Bean
    public WebClient mediaServiceWebClient(McpChartRenderProperties props, WebClient.Builder builder) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }
}
