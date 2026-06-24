package dev.fedorov.ailife.mcp.reddit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
public class HttpConfig {

    /**
     * Mints the app-only OAuth token. HTTP Basic with the app id:secret + the policy {@code User-Agent}.
     * The source only calls this when both creds are set, so the (possibly empty) basic header here is
     * never sent on a real request without creds.
     */
    @Bean
    public WebClient redditAuthWebClient(McpRedditProperties props, WebClient.Builder builder) {
        String basic = Base64.getEncoder().encodeToString(
                (props.getClientId() + ":" + props.getClientSecret()).getBytes(StandardCharsets.UTF_8));
        return builder.clone()
                .baseUrl(props.getAuthBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.USER_AGENT, props.getUserAgent())
                .build();
    }

    /**
     * Reads listings from the Reddit OAuth API. The bearer token is added per request by the source;
     * the policy {@code User-Agent} is pinned here. {@code .clone()} avoids base-URL leakage.
     */
    @Bean
    public WebClient redditApiWebClient(McpRedditProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getApiBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, props.getUserAgent())
                .build();
    }
}
