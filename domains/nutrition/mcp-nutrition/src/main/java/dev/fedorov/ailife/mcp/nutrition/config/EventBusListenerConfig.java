package dev.fedorov.ailife.mcp.nutrition.config;

import dev.fedorov.ailife.bus.EventBusListenerContainer;
import dev.fedorov.ailife.bus.EventBusProperties;
import dev.fedorov.ailife.mcp.nutrition.bus.BasketCapturedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Registers mcp-nutrition as a bus <b>consumer</b> (IA-b): an {@link EventBusListenerContainer} that
 * runs the LISTEN loop with the context and dispatches each event to {@link BasketCapturedHandler}
 * (which forwards {@code basket.captured} to nutritionist-agent). The producer side
 * ({@code OutboxPublisher}) comes from {@code EventBusConfig}, imported in {@code McpNutritionApplication}.
 * Mirrors notifier-service / memory-service consumer wiring.
 */
@Configuration
public class EventBusListenerConfig {

    @Bean
    public EventBusListenerContainer basketCapturedListenerContainer(DataSource dataSource,
                                                                     EventBusProperties props,
                                                                     BasketCapturedHandler handler) {
        return new EventBusListenerContainer(dataSource, props, handler::onEvent);
    }
}
