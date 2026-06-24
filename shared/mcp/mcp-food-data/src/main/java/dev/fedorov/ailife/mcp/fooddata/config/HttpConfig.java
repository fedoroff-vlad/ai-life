package dev.fedorov.ailife.mcp.fooddata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /**
     * Reads product JSON from Open Food Facts. The API policy asks for a descriptive
     * {@code User-Agent} identifying the app (name + version) so they can contact heavy users —
     * we comply (no key required).
     */
    @Bean
    public WebClient openFoodFactsWebClient(McpFoodDataProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getOpenFoodFactsUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-food-data 0.0.1 (https://github.com/vlad94fed)")
                .build();
    }
}
