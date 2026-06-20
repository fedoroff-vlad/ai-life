package dev.fedorov.ailife.mcp.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Sends queries to the SearXNG backing service ({@code /search?format=json}). */
    @Bean
    public WebClient searxngWebClient(McpWebProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getSearxngUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-web 0.0.1")
                .build();
    }
}
