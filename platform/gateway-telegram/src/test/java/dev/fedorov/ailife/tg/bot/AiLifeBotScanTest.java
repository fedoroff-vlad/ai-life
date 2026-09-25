package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.tg.identity.InviteOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /start} prefix dispatch (IN-f1): two unrelated deep-links share that one path, and which
 * one a payload is must be decided by its prefix alone.
 *
 * <p>The regression these tests exist for is a <b>printed</b> one: a box label that fell through to the
 * invite redeemer would answer "unknown invite" to a sticker already glued on a box, and a family invite
 * mistaken for a box would break onboarding. Both directions are asserted here.
 */
class AiLifeBotScanTest {

    // A low-entropy stand-in for an opaque qr_token (a hex-looking literal beside "token" trips gitleaks).
    private static final String LABEL_ID = "demo-b07";

    private final TelegramClient client = mock(TelegramClient.class);
    private final MessageProcessor processor = mock(MessageProcessor.class);
    private final TypingIndicator typing = mock(TypingIndicator.class);
    private final AiLifeBot bot = new AiLifeBot(client, processor, typing);

    AiLifeBotScanTest() {
        when(typing.start(any())).thenReturn(mock(TypingIndicator.Handle.class));
    }

    /** Scenario: camera scan — opening the label's deep-link replies with that container's card. */
    @Test
    void boxDeepLinkOpensTheContainerCard() throws Exception {
        when(processor.showContainer(any(), eq(LABEL_ID))).thenReturn(Mono.just(
                new IntentResponse("inventory", "Коробка B-07 «кухня — посуда», кладовка: https://c", "m")));

        bot.consume(startUpdate("box_" + LABEL_ID));

        assertThat(captureSend().getText()).contains("B-07").contains("https://c");
        // A scan is never a redemption — one wrong branch here and a glued-on sticker answers
        // "unknown invite" forever.
        verify(processor, never()).redeemInvite(any(), any());
    }

    /** Scenario: invite token still redeems — the prefix dispatch must not touch the 4b path. */
    @Test
    void plainStartTokenStillRedeemsTheInvite() throws Exception {
        when(processor.redeemInvite(any(), eq("Ab3-_tokenXYZ"))).thenReturn(Mono.just(
                new InviteOutcome("Ты в семье!", null, null)));

        bot.consume(startUpdate("Ab3-_tokenXYZ"));

        assertThat(captureSend().getText()).isEqualTo("Ты в семье!");
        verify(processor, never()).showContainer(any(), any());
    }

    /** A prefix with nothing after it is not a box — it degrades to the invite path's graceful answer. */
    @Test
    void anEmptyBoxTokenFallsBackToTheInvitePath() throws Exception {
        when(processor.redeemInvite(any(), eq("box_"))).thenReturn(Mono.just(
                new InviteOutcome("Ссылка не сработала.", null, null)));

        bot.consume(startUpdate("box_"));

        verify(processor, never()).showContainer(any(), any());
        assertThat(captureSend().getText()).isEqualTo("Ссылка не сработала.");
    }

    // ----- helpers ----------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SendMessage captureSend() throws Exception {
        ArgumentCaptor<BotApiMethod> cap = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(client, atLeastOnce()).execute(cap.capture());
        return cap.getAllValues().stream()
                .filter(SendMessage.class::isInstance).map(SendMessage.class::cast)
                .reduce((a, b) -> b).orElseThrow();
    }

    private static Update startUpdate(String payload) {
        Chat chat = Chat.builder().id(999L).type("private").build();
        Message m = new Message();
        m.setMessageId(10);
        m.setChat(chat);
        m.setFrom(User.builder().id(5L).isBot(false).firstName("Vlad").languageCode("ru").build());
        m.setText("/start " + payload);
        Update u = new Update();
        u.setMessage(m);
        return u;
    }
}
