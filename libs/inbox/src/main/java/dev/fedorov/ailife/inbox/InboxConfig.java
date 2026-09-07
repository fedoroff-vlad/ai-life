package dev.fedorov.ailife.inbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring wiring for the durable inbox. {@code @Import} this from an ingress service's config (it lives
 * outside any service's component-scan root).
 *
 * <p>Provides the producer ({@link InboxWriter}) automatically. The redrive side is opt-in: the ingress
 * registers its own {@link InboxRedriverContainer} bean with its handler (the handler re-dispatches and
 * delivers, which is service-specific, so it can't be auto-created here).
 */
@Configuration
@EnableConfigurationProperties(InboxProperties.class)
public class InboxConfig {

    @Bean
    @ConditionalOnMissingBean
    public InboxWriter inboxWriter(JdbcTemplate jdbc, InboxProperties props) {
        return new InboxWriter(jdbc, props.getInitialDelay());
    }
}
