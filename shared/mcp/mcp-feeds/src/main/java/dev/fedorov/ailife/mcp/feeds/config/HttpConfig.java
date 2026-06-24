package dev.fedorov.ailife.mcp.feeds.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /**
     * Fetches RSS/Atom feed bodies (absolute URLs) and public Telegram channel pages. No base URL —
     * RSS feeds are arbitrary absolute URLs; the source builds the Telegram URL from the configured
     * base. Pins the policy {@code User-Agent}. {@code .clone()} avoids shared-builder leakage.
     */
    @Bean
    public WebClient feedsWebClient(McpFeedsProperties props, WebClient.Builder builder) {
        return builder.clone()
                .defaultHeader(HttpHeaders.USER_AGENT, props.getUserAgent())
                .build();
    }
}
