package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.tg.config.GatewayProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

/**
 * Registers the bot with the long-polling application after Spring is ready. If no
 * {@code GATEWAY_TELEGRAM_BOT_TOKEN} is configured the bot is skipped (the rest of the
 * service still starts — useful for CI and IDE runs without a real token).
 */
@Component
public class BotRegistration {

    private static final Logger log = LoggerFactory.getLogger(BotRegistration.class);

    private final GatewayProperties props;
    private final MessageProcessor processor;
    private final TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication();

    @Autowired
    public BotRegistration(GatewayProperties props, MessageProcessor processor) {
        this.props = props;
        this.processor = processor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerBot() {
        if (!props.getTelegram().isConfigured()) {
            log.warn("GATEWAY_TELEGRAM_BOT_TOKEN is empty — Telegram bot will not start.");
            return;
        }
        try {
            String token = props.getTelegram().getBotToken();
            AiLifeBot bot = new AiLifeBot(token, processor);
            application.registerBot(token, bot);
            log.info("Telegram bot @{} registered, long-polling started",
                    props.getTelegram().getBotUsername());
        } catch (Exception e) {
            log.error("Failed to start Telegram bot", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            application.close();
        } catch (Exception e) {
            log.warn("Failure while shutting down Telegram bots application", e);
        }
    }
}
