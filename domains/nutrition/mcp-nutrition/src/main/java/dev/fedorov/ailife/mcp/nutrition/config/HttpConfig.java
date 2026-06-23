package dev.fedorov.ailife.mcp.nutrition.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP wiring for the bus consumer (IA-b): a {@link WebClient} pointed at nutritionist-agent
 * so the {@code basket.captured} handler can forward the event to its {@code /internal/basket-event}.
 */
@Configuration
public class HttpConfig {

    @Bean
    public WebClient nutritionistAgentWebClient(WebClient.Builder builder, McpNutritionProperties props) {
        return builder.clone().baseUrl(props.getNutritionistAgentUrl()).build();
    }
}
