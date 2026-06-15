package dev.fedorov.ailife.bus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring wiring for the event bus. {@code @Import} this from a service's application
 * class (it lives outside any service's component-scan root).
 *
 * <p>Provides the producer ({@link OutboxPublisher}) automatically. The consumer side
 * is opt-in: a service that wants to react registers its own
 * {@link EventBusListenerContainer} bean with its handler (the handler is
 * service-specific, so it can't be auto-created here).
 */
@Configuration
@EnableConfigurationProperties(EventBusProperties.class)
public class EventBusConfig {

    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(JdbcTemplate jdbc, EventBusProperties props) {
        return new OutboxPublisher(jdbc, props.getChannel());
    }
}
