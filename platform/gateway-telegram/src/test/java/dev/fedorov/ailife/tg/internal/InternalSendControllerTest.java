package dev.fedorov.ailife.tg.internal;

import dev.fedorov.ailife.contracts.notify.InternalSendRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Bot token left empty so the live OkHttp bot doesn't attempt long-polling against
 * Telegram. {@link MockitoBean} injects a stand-in {@link TelegramClient} so the
 * conditional bean is satisfied for the controller path only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                        "gateway.telegram.bot-token=",
                        "gateway.internal-api-token=secret-test-token"
                })
@AutoConfigureWebTestClient
class InternalSendControllerTest {

    @MockitoBean
    TelegramClient telegramClient;

    @Autowired
    WebTestClient client;

    @Test
    void validBearerForwardsToTelegram() throws Exception {
        client.post().uri("/internal/send")
                .header("Authorization", "Bearer secret-test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InternalSendRequest(987654321L, "hi"))
                .exchange()
                .expectStatus().isNoContent();

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo("987654321");
        assertThat(sent.getText()).isEqualTo("hi");
    }

    @Test
    void wrongBearerIs401() throws Exception {
        client.post().uri("/internal/send")
                .header("Authorization", "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InternalSendRequest(1L, "hi"))
                .exchange()
                .expectStatus().isUnauthorized();

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void missingBearerIs400OrUnauthorized() throws Exception {
        // Spring rejects @RequestHeader-mandatory header with 400 before our handler runs;
        // either 400 or 401 is acceptable for the security goal of "must present auth".
        client.post().uri("/internal/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InternalSendRequest(1L, "hi"))
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isIn(400, 401));

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void blankTextIs400() throws Exception {
        client.post().uri("/internal/send")
                .header("Authorization", "Bearer secret-test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InternalSendRequest(1L, "  "))
                .exchange()
                .expectStatus().isBadRequest();

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }
}
