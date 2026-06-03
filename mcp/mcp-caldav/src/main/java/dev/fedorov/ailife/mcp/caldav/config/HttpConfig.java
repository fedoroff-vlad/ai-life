package dev.fedorov.ailife.mcp.caldav.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    @Bean
    public WebClient caldavWebClient(McpCaldavProperties props, WebClient.Builder builder) {
        WebClient.Builder b = builder
                .baseUrl(props.getUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-caldav 0.0.1");
        if (props.hasCredentials()) {
            b = b.filter(ExchangeFilterFunctions
                    .basicAuthentication(props.getUser(), props.getPassword()));
        }
        return b.build();
    }
}
