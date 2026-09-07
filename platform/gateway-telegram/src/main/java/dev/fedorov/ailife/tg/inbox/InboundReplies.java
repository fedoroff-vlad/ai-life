package dev.fedorov.ailife.tg.inbox;

/**
 * User-facing replies for the durable-inbox degraded paths (#633), localised RU/EN off the Telegram
 * language code. Kept in one place so the in-request "queued" reply and the redrive dead-letter reply
 * stay consistent.
 */
public final class InboundReplies {

    private InboundReplies() {
    }

    private static boolean ru(String languageCode) {
        return languageCode == null || languageCode.startsWith("ru");
    }

    /** Shown in-request when a downstream outage was hit but the message was durably queued for redrive. */
    public static String queued(String languageCode) {
        return ru(languageCode)
                ? "⏳ Сервис временно недоступен — поставил сообщение в очередь, отвечу, как только починится."
                : "⏳ Service is temporarily unavailable — I've queued your message and will reply once it's back.";
    }

    /** Shown when a message could not be processed even after all redrive attempts (poison → DEAD). */
    public static String deadLetter(String languageCode) {
        return ru(languageCode)
                ? "⚠️ Не смог обработать твоё сообщение даже после нескольких попыток. Попробуй, пожалуйста, ещё раз."
                : "⚠️ I couldn't process your message even after several retries. Please try sending it again.";
    }
}
