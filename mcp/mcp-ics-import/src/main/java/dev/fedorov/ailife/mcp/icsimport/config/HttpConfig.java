package dev.fedorov.ailife.mcp.icsimport.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    @Bean
    public WebClient caldavWebClient(McpIcsImportProperties props, WebClient.Builder builder) {
        WebClient.Builder b = builder.clone()
                .baseUrl(props.getCaldavUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-ics-import 0.0.1");
        if (props.hasCaldavCredentials()) {
            b = b.filter(ExchangeFilterFunctions
                    .basicAuthentication(props.getCaldavUser(), props.getCaldavPassword()));
        }
        return b.build();
    }

    /** Open-internet WebClient for fetching ICS bodies from user-supplied URLs. */
    @Bean
    public WebClient icsHttpClient(WebClient.Builder builder) {
        return builder.clone()
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-ics-import 0.0.1")
                .defaultHeader(HttpHeaders.ACCEPT, "text/calendar, text/plain, */*")
                .build();
    }
}
