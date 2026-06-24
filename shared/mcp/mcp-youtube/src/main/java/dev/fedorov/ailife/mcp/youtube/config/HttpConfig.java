package dev.fedorov.ailife.mcp.youtube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /**
     * Reads search results from the YouTube Data API v3. The API key is a per-request query param
     * (added by the source), not a header — so the client just pins the base URL. {@code .clone()}
     * off the shared builder avoids base-URL leakage into other WebClients.
     */
    @Bean
    public WebClient youtubeWebClient(McpYoutubeProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getApiBaseUrl())
                .build();
    }
}
