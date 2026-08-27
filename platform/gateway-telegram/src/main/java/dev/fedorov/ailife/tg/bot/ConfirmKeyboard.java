package dev.fedorov.ailife.tg.bot;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The shared inline-button primitive (#489 RU-2; the proactive snooze/dismiss buttons of #487 PX-4 will
 * extend the same pattern). Renders a two-button <b>Да / Нет</b> keyboard under a binary confirm prompt
 * and decodes a button tap's {@code callback_data} back into the affirmative / negative <em>text</em> a
 * route-locked {@code /resume} expects — so a tap is exactly the "да"/"нет" the user would otherwise type,
 * reusing the whole conversation-lock resume path unchanged (no contract or agent change).
 */
final class ConfirmKeyboard {

    /** {@code callback_data} payloads. Kept tiny — Telegram caps {@code callback_data} at 64 bytes. */
    static final String CONFIRM = "cf:y";
    static final String CANCEL = "cf:n";

    /** The words a tap maps to — {@code PickConfirmActRunner} treats "да" as affirmative, anything else declines. */
    private static final String AFFIRMATIVE_TEXT = "да";
    private static final String NEGATIVE_TEXT = "нет";

    private ConfirmKeyboard() {
    }

    /** The two-button keyboard shown under a confirm prompt: localised labels, stable callback ids. */
    static InlineKeyboardMarkup markup(String languageCode) {
        boolean ru = languageCode == null || languageCode.startsWith("ru");
        InlineKeyboardButton yes = InlineKeyboardButton.builder()
                .text(ru ? "✅ Да" : "✅ Yes").callbackData(CONFIRM).build();
        InlineKeyboardButton no = InlineKeyboardButton.builder()
                .text(ru ? "✖️ Нет" : "✖️ No").callbackData(CANCEL).build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(yes, no))
                .build();
    }

    /** The text a tap maps to — the same word a user would type — or {@code null} when the data isn't ours. */
    static String textFor(String callbackData) {
        if (CONFIRM.equals(callbackData)) {
            return AFFIRMATIVE_TEXT;
        }
        if (CANCEL.equals(callbackData)) {
            return NEGATIVE_TEXT;
        }
        return null;
    }
}
