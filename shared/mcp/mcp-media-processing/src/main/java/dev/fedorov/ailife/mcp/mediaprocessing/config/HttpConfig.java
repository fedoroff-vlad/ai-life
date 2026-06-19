package dev.fedorov.ailife.mcp.mediaprocessing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Reads stored media bytes from media-service by object id. */
    @Bean
    public WebClient mediaWebClient(McpMediaProcessingProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getMediaServiceUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-media-processing 0.0.1")
                .build();
    }
}
