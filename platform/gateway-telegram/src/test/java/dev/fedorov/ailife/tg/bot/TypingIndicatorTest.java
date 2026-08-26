package dev.fedorov.ailife.tg.bot;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TypingIndicator} (road-test #489 — quick ack): a mock {@link TelegramClient} +
 * a mock scheduler exercise the immediate fire, the keep-alive refresh, the handle-cancels-refresh
 * contract, and the best-effort swallow — no real Telegram or timer.
 */
class TypingIndicatorTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);

    private final TypingIndicator typing =
            new TypingIndicator(client, scheduler, Duration.ofSeconds(4));

    @Test
    void startFiresTypingImmediatelyAndSchedulesTheRefresh() throws Exception {
        doReturn(future).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        typing.start(99L);

        ArgumentCaptor<SendChatAction> sent = ArgumentCaptor.forClass(SendChatAction.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getAction()).isEqualTo("typing");
        assertThat(sent.getValue().getChatId()).isEqualTo("99");

        // A refresh is scheduled at the 4s cadence so the indicator survives a long round-trip.
        verify(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), eq(4000L), eq(4000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void refreshTaskResendsTyping() throws Exception {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler)
                .scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        typing.start(7L);
        // The immediate fire is one send; running the scheduled task simulates the 4s refresh tick.
        task.getValue().run();

        verify(client, atLeastOnce()).execute(any(SendChatAction.class));
        verify(client, org.mockito.Mockito.times(2)).execute(any(SendChatAction.class));
    }

    @Test
    void closingTheHandleCancelsTheRefresh() {
        doReturn(future).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        TypingIndicator.Handle handle = typing.start(1L);
        handle.close();

        verify(future).cancel(false);
    }

    @Test
    void aSendFailureIsSwallowedNeverBreaksTheReply() throws Exception {
        doReturn(future).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendChatAction.class));

        assertThatCode(() -> typing.start(5L).close()).doesNotThrowAnyException();
    }
}
