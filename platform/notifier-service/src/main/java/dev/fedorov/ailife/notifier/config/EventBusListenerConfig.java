package dev.fedorov.ailife.notifier.config;

import dev.fedorov.ailife.bus.EventBusListenerContainer;
import dev.fedorov.ailife.bus.EventBusProperties;
import dev.fedorov.ailife.notifier.bus.NotifyEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Registers notifier-service as a bus <b>consumer</b>: an {@link EventBusListenerContainer}
 * that starts/stops the LISTEN loop with the context and dispatches each event to
 * {@link NotifyEventHandler}. The producer side ({@code OutboxPublisher}) comes from
 * {@code EventBusConfig}, imported in {@code NotifierApplication}.
 */
@Configuration
public class EventBusListenerConfig {

    @Bean
    public EventBusListenerContainer notifyListenerContainer(DataSource dataSource,
                                                             EventBusProperties props,
                                                             NotifyEventHandler handler) {
        return new EventBusListenerContainer(dataSource, props, handler::onEvent);
    }
}
