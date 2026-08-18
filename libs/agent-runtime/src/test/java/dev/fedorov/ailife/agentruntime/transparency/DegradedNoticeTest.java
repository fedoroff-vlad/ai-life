package dev.fedorov.ailife.agentruntime.transparency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DegradedNoticeTest {

    @Test
    void appendsNoticeAsTrailingMarkerBlock() {
        String out = DegradedNotice.append("Готово.", "не смог сохранить — попробуйте позже");
        assertThat(out)
                .startsWith("Готово.")
                .contains(DegradedNotice.MARKER)
                .isEqualTo("Готово.\n\n⚠️ не смог сохранить — попробуйте позже");
    }

    @Test
    void blankNoticeLeavesTextUnchanged() {
        assertThat(DegradedNotice.append("Готово.", null)).isEqualTo("Готово.");
        assertThat(DegradedNotice.append("Готово.", "   ")).isEqualTo("Готово.");
    }

    @Test
    void blankTextYieldsNoticeAlone() {
        assertThat(DegradedNotice.append(null, "не сохранил")).isEqualTo("⚠️ не сохранил");
        assertThat(DegradedNotice.append("", "не сохранил")).isEqualTo("⚠️ не сохранил");
    }

    @Test
    void nullTextAndNullNoticeYieldEmptyString() {
        assertThat(DegradedNotice.append(null, null)).isEmpty();
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(DegradedNotice.append("  Готово.  ", "  не сохранил  "))
                .isEqualTo("Готово.\n\n⚠️ не сохранил");
    }
}
