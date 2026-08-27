package dev.fedorov.ailife.tg.bot;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfirmKeyboard} (#489 RU-2): the two-button confirm keyboard carries the stable
 * callback ids the tap-decoder maps back to "да"/"нет", labels localise, and unknown data decodes to null.
 */
class ConfirmKeyboardTest {

    @Test
    void markupHasYesNoButtonsWithStableCallbackData() {
        InlineKeyboardMarkup markup = ConfirmKeyboard.markup("ru");

        assertThat(markup.getKeyboard()).hasSize(1);
        InlineKeyboardRow row = markup.getKeyboard().get(0);
        assertThat(row).hasSize(2);
        assertThat(row.get(0).getText()).contains("Да");
        assertThat(row.get(0).getCallbackData()).isEqualTo(ConfirmKeyboard.CONFIRM);
        assertThat(row.get(1).getText()).contains("Нет");
        assertThat(row.get(1).getCallbackData()).isEqualTo(ConfirmKeyboard.CANCEL);
    }

    @Test
    void labelsLocaliseToEnglishForNonRussian() {
        InlineKeyboardRow row = ConfirmKeyboard.markup("en").getKeyboard().get(0);
        assertThat(row.get(0).getText()).contains("Yes");
        assertThat(row.get(1).getText()).contains("No");
        // Callback ids stay stable across locales — the decoder keys off them, not the label.
        assertThat(row.get(0).getCallbackData()).isEqualTo(ConfirmKeyboard.CONFIRM);
    }

    @Test
    void nullLanguageDefaultsToRussian() {
        InlineKeyboardRow row = ConfirmKeyboard.markup(null).getKeyboard().get(0);
        assertThat(row.get(0).getText()).contains("Да");
    }

    @Test
    void textForMapsTapsBackToTheWordTheUserWouldType() {
        assertThat(ConfirmKeyboard.textFor(ConfirmKeyboard.CONFIRM)).isEqualTo("да");
        assertThat(ConfirmKeyboard.textFor(ConfirmKeyboard.CANCEL)).isEqualTo("нет");
    }

    @Test
    void textForUnknownDataIsNull() {
        assertThat(ConfirmKeyboard.textFor("something-else")).isNull();
        assertThat(ConfirmKeyboard.textFor(null)).isNull();
    }
}
