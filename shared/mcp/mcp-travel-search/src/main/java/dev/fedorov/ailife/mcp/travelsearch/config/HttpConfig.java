package dev.fedorov.ailife.mcp.travelsearch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per Travelpayouts host (they live on different domains), each {@code clone()}d off
 * the shared builder to avoid base-URL leakage: {@code aviasales} (flight prices), {@code hotellook}
 * (hotel cache) and {@code autocomplete} (place → IATA code).
 */
@Configuration
public class HttpConfig {

    private static final String UA = "ai-life/mcp-travel-search 0.0.1";

    @Bean
    public WebClient aviasalesWebClient(McpTravelSearchProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getAviasalesApiUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, UA)
                .build();
    }

    @Bean
    public WebClient hotellookWebClient(McpTravelSearchProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getHotellookApiUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, UA)
                .build();
    }

    @Bean
    public WebClient autocompleteWebClient(McpTravelSearchProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getAutocompleteUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, UA)
                .build();
    }
}
