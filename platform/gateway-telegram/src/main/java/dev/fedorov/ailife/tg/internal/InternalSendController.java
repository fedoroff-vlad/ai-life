package dev.fedorov.ailife.tg.internal;

import dev.fedorov.ailife.contracts.notify.InternalSendRequest;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Internal-only endpoint for in-cluster services (notifier-service, future agents).
 * Guarded by {@code Authorization: Bearer ${GATEWAY_INTERNAL_API_TOKEN}}. The bot
 * token never leaves this service.
 */
@RestController
@RequestMapping("/internal")
public class InternalSendController {

    private static final Logger log = LoggerFactory.getLogger(InternalSendController.class);

    private final GatewayProperties props;
    private final ObjectProvider<TelegramClient> clientProvider;

    public InternalSendController(GatewayProperties props,
                                  ObjectProvider<TelegramClient> clientProvider) {
        this.props = props;
        this.clientProvider = clientProvider;
    }

    @PostMapping(path = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> send(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                     @RequestBody InternalSendRequest request) {
        if (!authorized(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
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

    private boolean authorized(String authHeader) {
        String expected = props.getInternalApiToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (authHeader == null) {
            return false;
        }
        String prefix = "Bearer ";
        if (!authHeader.startsWith(prefix)) {
            return false;
        }
        return constantTimeEquals(authHeader.substring(prefix.length()), expected);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
