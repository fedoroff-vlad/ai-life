package dev.fedorov.ailife.memory.config;

import dev.fedorov.ailife.bus.EventBusListenerContainer;
import dev.fedorov.ailife.bus.EventBusProperties;
import dev.fedorov.ailife.memory.bus.MessageCaptureHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Registers memory-service as a bus <b>consumer</b>: an {@link EventBusListenerContainer}
 * that starts/stops the LISTEN loop with the context and dispatches each event to
 * {@link MessageCaptureHandler} (memory-from-chat). The producer side
 * ({@code OutboxPublisher}) comes from {@code EventBusConfig}, imported in
 * {@code MemoryServiceApplication}.
 */
@Configuration
public class EventBusListenerConfig {

    @Bean
    public EventBusListenerContainer messageCaptureListenerContainer(DataSource dataSource,
                                                                     EventBusProperties props,
                                                                     MessageCaptureHandler handler) {
        return new EventBusListenerContainer(dataSource, props, handler::onEvent);
    }
}
