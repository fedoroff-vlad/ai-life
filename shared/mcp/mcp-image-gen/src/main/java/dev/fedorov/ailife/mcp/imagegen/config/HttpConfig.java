package dev.fedorov.ailife.mcp.imagegen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Uploads generated images to media-service ({@code POST /v1/media}). */
    @Bean
    public WebClient mediaServiceWebClient(McpImageGenProperties props, WebClient.Builder builder) {
        return builder.clone().baseUrl(props.getMediaServiceUrl()).build();
    }

    /** Talks to the self-hosted model server when {@code engine=local}. */
    @Bean
    public WebClient localModelWebClient(McpImageGenProperties props, WebClient.Builder builder) {
        return builder.clone().baseUrl(props.getLocalUrl()).build();
    }
}
