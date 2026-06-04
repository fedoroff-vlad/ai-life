package dev.fedorov.ailife.tg.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Single source of {@link TelegramClient} for the whole service. Created only when
 * {@code gateway.telegram.bot-token} is non-empty so the rest of the app boots cleanly
 * in CI / IDE / unit tests without a real bot token. Consumers (bot polling,
 * {@code /internal/send}) inject through {@code ObjectProvider} to tolerate absence.
 */
@Configuration
public class TelegramClientConfig {

    @Bean
    @ConditionalOnExpression("'${gateway.telegram.bot-token:}' != ''")
    public TelegramClient telegramClient(GatewayProperties props) {
        return new OkHttpTelegramClient(props.getTelegram().getBotToken());
    }
}
