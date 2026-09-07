package dev.fedorov.ailife.tg.internal;

import dev.fedorov.ailife.contracts.notify.InternalSendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Internal-only endpoint for in-cluster services (notifier-service, future agents). Access to
 * {@code /internal/*} is guarded centrally by the shared-secret filter in {@code platform-common}
 * ({@code INTERNAL_SHARED_SECRET}, #630/ADR-0007) — this controller no longer checks it itself. The
 * bot token never leaves this service.
 */
@RestController
@RequestMapping("/internal")
public class InternalSendController {

    private static final Logger log = LoggerFactory.getLogger(InternalSendController.class);

    private final ObjectProvider<TelegramClient> clientProvider;

    public InternalSendController(ObjectProvider<TelegramClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @PostMapping(path = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> send(@RequestBody InternalSendRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        TelegramClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            client.execute(SendMessage.builder()
                    .chatId(request.telegramUserId())
                    .text(request.text())
                    .build());
            return ResponseEntity.noContent().build();
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message to chat {}", request.telegramUserId(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
