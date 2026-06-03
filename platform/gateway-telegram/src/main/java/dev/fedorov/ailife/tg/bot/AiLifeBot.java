package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.MessageScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Long-polling Telegram consumer. Translates Telegram updates into
 * {@link MessageProcessor.IncomingMessage}, blocks on the reactive pipeline (we are
 * already on a polling thread), and replies via {@link TelegramClient}.
 */
public class AiLifeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiLifeBot.class);

    private final TelegramClient client;
    private final MessageProcessor processor;

    public AiLifeBot(String botToken, MessageProcessor processor) {
        this(new OkHttpTelegramClient(botToken), processor);
    }

    AiLifeBot(TelegramClient client, MessageProcessor processor) {
        this.client = client;
        this.processor = processor;
    }

    @Override
    public void consume(Update update) {
        if (update.getMessage() == null || !update.getMessage().hasText()) {
            return;
        }
        Message msg = update.getMessage();
        User from = msg.getFrom();
        if (from == null || from.getIsBot()) {
            return;
        }

        var incoming = new MessageProcessor.IncomingMessage(
                from.getId(),
                displayNameOf(from),
                from.getLanguageCode(),
                msg.getText(),
                scopeFor(msg),
                String.valueOf(msg.getMessageId()));

        try {
            var response = processor.process(incoming).block();
            String reply = response != null && response.text() != null
                    ? response.text()
                    : "(no response)";
            send(msg.getChatId(), reply);
        } catch (Exception e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
            send(msg.getChatId(), "Sorry, something broke. Please try again.");
        }
    }

    private void send(Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send reply to chat {}", chatId, e);
        }
    }

    private static String displayNameOf(User from) {
        if (from.getFirstName() != null && from.getLastName() != null) {
            return from.getFirstName() + " " + from.getLastName();
        }
        if (from.getFirstName() != null) {
            return from.getFirstName();
        }
        if (from.getUserName() != null) {
            return from.getUserName();
        }
        return "user-" + from.getId();
    }

    private static MessageScope scopeFor(Message msg) {
        return msg.isGroupMessage() || msg.isSuperGroupMessage()
                ? MessageScope.GROUP_CHAT
                : MessageScope.PRIVATE;
    }
}
