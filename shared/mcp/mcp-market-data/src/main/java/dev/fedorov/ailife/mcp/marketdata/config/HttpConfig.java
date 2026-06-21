package dev.fedorov.ailife.mcp.marketdata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    /** Reads quote CSV from Stooq ({@code /q/l/?s=&f=&e=csv}). */
    @Bean
    public WebClient stooqWebClient(McpMarketDataProperties props, WebClient.Builder builder) {
        return builder.clone()
                .baseUrl(props.getStooqUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-life/mcp-market-data 0.0.1")
                .build();
    }
}
