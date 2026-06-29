package dev.fedorov.ailife.mcp.weather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Reads forecast JSON from Open-Meteo ({@code /v1/forecast?latitude=&longitude=&daily=...}). */
    @Bean
    public WebClient openMeteoWebClient(McpWeatherProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getOpenMeteoUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-weather 0.0.1")
                .build();
    }
}
