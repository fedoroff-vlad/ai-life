package dev.fedorov.ailife.tg.config;

import dev.fedorov.ailife.inbox.InboxConfig;
import dev.fedorov.ailife.inbox.InboxProperties;
import dev.fedorov.ailife.inbox.InboxRedriverContainer;
import dev.fedorov.ailife.tg.inbox.GatewayInboxHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * Wires the durable inbound inbox (#633) into the gateway. {@code @Import(InboxConfig.class)} provides
 * the always-on {@code InboxWriter} (used by {@code MessageProcessor} to persist-before-process).
 *
 * <p>The redrive container starts <b>only when the bot is configured</b> (same
 * {@code gateway.telegram.bot-token} gate as the {@code TelegramClient}) — there's no point redriving
 * when there's no bot to deliver the answer, and this keeps token-less CI/IDE contexts (and their
 * DB-less slice tests) from starting a background loop. It also honors {@code inbox.enabled}.
 */
@Configuration
@Import(InboxConfig.class)
public class InboxWiringConfig {

    @Bean
    @ConditionalOnExpression("'${gateway.telegram.bot-token:}' != ''")
    public InboxRedriverContainer inboxRedriverContainer(DataSource dataSource,
                                                         InboxProperties props,
                                                         GatewayInboxHandler handler) {
        return new InboxRedriverContainer(dataSource, props, handler, handler);
    }
}
