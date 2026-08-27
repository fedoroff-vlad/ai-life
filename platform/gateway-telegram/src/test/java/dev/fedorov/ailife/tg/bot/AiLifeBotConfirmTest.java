package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RU-2 inline-confirm wiring in {@link AiLifeBot} (#489): a binary-confirm reply is sent
 * with a Да/Нет keyboard, a plain reply is not, and a button tap (callback query) is decoded to "да"/"нет"
 * and routed like a typed reply — with the callback answered and the prompt's keyboard stripped.
 */
class AiLifeBotConfirmTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final MessageProcessor processor = mock(MessageProcessor.class);
    private final TypingIndicator typing = mock(TypingIndicator.class);
    private final AiLifeBot bot = new AiLifeBot(client, processor, typing);
    private final ObjectMapper json = new ObjectMapper();

    AiLifeBotConfirmTest() {
        when(typing.start(any())).thenReturn(mock(TypingIndicator.Handle.class));
    }

    @Test
    void binaryConfirmReplyIsSentWithYesNoKeyboard() throws Exception {
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "delete-task");
        pending.put("confirm", true);
        when(processor.process(any())).thenReturn(Mono.just(
                new IntentResponse("tasks", "Удалить «купить молоко»?", "m", pending)));

        bot.consume(textUpdate("удали задачу"));

        SendMessage sent = captureSend();
        assertThat(sent.getText()).contains("Удалить");
        assertThat(sent.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(markup.getKeyboard().get(0)).hasSize(2);
    }

    @Test
    void openQuestionPendingActionGetsNoKeyboard() throws Exception {
        // A pendingAction WITHOUT the confirm hint (a clarify / sharing "личное-общее?") expects free text.
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "sharing-confirm");
        when(processor.process(any())).thenReturn(Mono.just(
                new IntentResponse("tasks", "Личное или общее?", "m", pending)));

        bot.consume(textUpdate("добавь задачу"));

        assertThat(captureSend().getReplyMarkup()).isNull();
    }

    @Test
    void plainReplyGetsNoKeyboard() throws Exception {
        when(processor.process(any())).thenReturn(Mono.just(
                new IntentResponse("echo", "Привет", "m")));

        bot.consume(textUpdate("привет"));

        assertThat(captureSend().getReplyMarkup()).isNull();
    }

    @Test
    void confirmTapIsDecodedToDaAndRoutedLikeATypedReply() throws Exception {
        ArgumentCaptor<MessageProcessor.IncomingMessage> routed =
                ArgumentCaptor.forClass(MessageProcessor.IncomingMessage.class);
        when(processor.process(routed.capture())).thenReturn(Mono.just(
                new IntentResponse("tasks", "Удалил.", "m")));

        bot.consume(callbackUpdate(ConfirmKeyboard.CONFIRM));

        // The tap became the affirmative text the route-locked /resume expects.
        assertThat(routed.getValue().text()).isEqualTo("да");
        // The callback is answered (spinner stops) and the prompt keyboard is stripped (one-shot).
        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client).execute(any(EditMessageReplyMarkup.class));
        // The agent's resume reply is sent back.
        assertThat(captureSend().getText()).isEqualTo("Удалил.");
    }

    @Test
    void cancelTapIsDecodedToNet() throws Exception {
        ArgumentCaptor<MessageProcessor.IncomingMessage> routed =
                ArgumentCaptor.forClass(MessageProcessor.IncomingMessage.class);
        when(processor.process(routed.capture())).thenReturn(Mono.just(
                new IntentResponse("tasks", "Отменил.", "m")));

        bot.consume(callbackUpdate(ConfirmKeyboard.CANCEL));

        assertThat(routed.getValue().text()).isEqualTo("нет");
    }

    @Test
    void foreignCallbackDataIsAcknowledgedButNotRouted() throws Exception {
        bot.consume(callbackUpdate("not-ours"));

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(processor, never()).process(any());
    }

    // ----- helpers ----------------------------------------------------------------------------------

    /**
     * The single {@link SendMessage} the bot executed. All outbound calls (send / answer-callback /
     * edit-markup) go through the one generic {@code execute(Method)}, so we capture every invocation and
     * pick the {@code SendMessage} — the last one, when a callback also answered and stripped the keyboard.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private SendMessage captureSend() throws Exception {
        ArgumentCaptor<BotApiMethod> cap = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(client, atLeastOnce()).execute(cap.capture());
        return cap.getAllValues().stream()
                .filter(SendMessage.class::isInstance).map(SendMessage.class::cast)
                .reduce((a, b) -> b).orElseThrow();
    }

    private static User user() {
        return User.builder().id(5L).isBot(false).firstName("Vlad").languageCode("ru").build();
    }

    private static Message message(String text) {
        Chat chat = Chat.builder().id(999L).type("private").build();
        Message m = new Message();
        m.setMessageId(10);
        m.setChat(chat);
        m.setFrom(user());
        m.setText(text);
        return m;
    }

    private static Update textUpdate(String text) {
        Update u = new Update();
        u.setMessage(message(text));
        return u;
    }

    private static Update callbackUpdate(String data) {
        CallbackQuery cq = new CallbackQuery();
        cq.setId("q1");
        cq.setData(data);
        cq.setFrom(user());
        cq.setMessage(message("Удалить «купить молоко»?"));
        Update u = new Update();
        u.setCallbackQuery(cq);
        return u;
    }
}
