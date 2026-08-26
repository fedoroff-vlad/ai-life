package dev.fedorov.ailife.tg.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shows Telegram's "печатает…" indicator while a slow flow runs, so the owner knows the bot heard them
 * instead of staring at silence (road-test <a href="https://github.com/fedoroff-vlad/ai-life/issues/489">#489</a>
 * — perceived latency / quick ack). Telegram's {@code sendChatAction} lapses after ~5s, but an
 * orchestrator round-trip (LLM + agent + MCP) routinely runs longer, so a single fire would blink out
 * mid-thought. This <b>keeps it alive</b>: fire once immediately, then re-send every {@link #DEFAULT_INTERVAL}
 * on a daemon scheduler (the long-poll thread is blocked in {@code process().block()}, so the refresh must
 * run off it) until the caller closes the returned {@link Handle} — which the bot does the instant the reply
 * is sent.
 *
 * <p><b>Purely cosmetic and best-effort:</b> every send is wrapped so a chat-action failure is logged and
 * swallowed — the typing hint must never break or delay the actual reply.
 */
public class TypingIndicator {

    private static final Logger log = LoggerFactory.getLogger(TypingIndicator.class);

    /** Refresh cadence — below Telegram's ~5s action expiry so the indicator never visibly blinks out. */
    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(4);

    /** The wire value Telegram expects for a "typing" chat action ({@code "typing"}). */
    private static final String TYPING = ActionType.TYPING.toString();

    private final TelegramClient client;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;

    public TypingIndicator(TelegramClient client) {
        this(client, defaultScheduler(), DEFAULT_INTERVAL);
    }

    TypingIndicator(TelegramClient client, ScheduledExecutorService scheduler, Duration interval) {
        this.client = client;
        this.scheduler = scheduler;
        this.intervalMs = interval.toMillis();
    }

    /**
     * Begin showing "typing…" in {@code chatId} — fired once now, then refreshed until the returned
     * {@link Handle} is closed. Never throws.
     */
    public Handle start(Long chatId) {
        sendTyping(chatId);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> sendTyping(chatId), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private void sendTyping(Long chatId) {
        try {
            client.execute(SendChatAction.builder()
                    .chatId(chatId.toString())
                    .action(TYPING)
                    .build());
        } catch (Exception e) {
            // Cosmetic only — never let a typing hint surface as an error or delay the reply.
            log.debug("typing indicator failed for chat {}: {}", chatId, e.toString());
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tg-typing");
            t.setDaemon(true);
            return t;
        });
    }

    /** Stops the typing refresh. {@link AutoCloseable} with an unchecked {@code close} for try-with-resources. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
