package dev.fedorov.ailife.tg.inbox;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.inbox.DeadLetterHandler;
import dev.fedorov.ailife.inbox.InboxHandler;
import dev.fedorov.ailife.inbox.InboxMessage;
import dev.fedorov.ailife.tg.orchestrator.OrchestratorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Redrive handler for the durable inbox (#633): re-dispatches a persisted inbound message to the
 * orchestrator and delivers the answer to the original Telegram chat. Registered as the
 * {@code InboxRedriverContainer}'s {@link InboxHandler} + {@link DeadLetterHandler}.
 *
 * <p>{@link #handle} throwing (orchestrator still down) tells the redriver to reschedule with backoff;
 * a clean return marks the row {@code PROCESSED}. When a message finally exhausts its attempts,
 * {@link #onDead} tells the user their message could not be processed instead of it vanishing silently.
 */
@Component
public class GatewayInboxHandler implements InboxHandler, DeadLetterHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayInboxHandler.class);

    private final OrchestratorClient orchestrator;
    private final ObjectProvider<TelegramClient> clientProvider;
    private final ObjectMapper json;

    public GatewayInboxHandler(OrchestratorClient orchestrator,
                               ObjectProvider<TelegramClient> clientProvider,
                               ObjectMapper json) {
        this.orchestrator = orchestrator;
        this.clientProvider = clientProvider;
        this.json = json;
    }

    @Override
    public void handle(InboxMessage message) throws Exception {
        InboundEnvelope env = json.readValue(message.payload(), InboundEnvelope.class);
        // Blocking is fine here — this runs on the redriver's own background thread, not an event loop.
        IntentResponse response = orchestrator.handle(env.message()).block();
        String text = response != null && response.text() != null ? response.text() : "(no response)";
        deliver(env.chatId(), text);
    }

    @Override
    public void onDead(InboxMessage message) {
        try {
            InboundEnvelope env = json.readValue(message.payload(), InboundEnvelope.class);
            deliver(env.chatId(), InboundReplies.deadLetter(env.languageCode()));
        } catch (Exception e) {
            log.warn("failed to deliver dead-letter notice for inbox row {}", message.id(), e);
        }
    }

    /**
     * Sends the reply to the chat via the bot client. Throws when delivery fails so the redriver keeps
     * the row for another attempt (an undelivered reply is a failed redrive, not a success).
     */
    private void deliver(long chatId, String text) throws TelegramApiException {
        TelegramClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("TelegramClient unavailable; cannot deliver redriven reply");
        }
        client.execute(SendMessage.builder().chatId(chatId).text(text).build());
    }
}
