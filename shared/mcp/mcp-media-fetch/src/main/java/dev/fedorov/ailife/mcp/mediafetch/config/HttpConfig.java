package dev.fedorov.ailife.mcp.mediafetch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Uploads extracted audio bytes to media-service ({@code POST /v1/media}) for {@code fetch_audio}. */
    @Bean
    public WebClient mediaWebClient(McpMediaFetchProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getMediaServiceUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-media-fetch 0.0.1")
                .build();
    }
}
