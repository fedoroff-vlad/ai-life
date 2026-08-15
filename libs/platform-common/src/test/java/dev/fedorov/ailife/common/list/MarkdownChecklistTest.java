package dev.fedorov.ailife.common.list;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit spec for the pure checklist body util (lists capability). Covers parse/render round-trip,
 * case-insensitive item matching, idempotent add, check-off, and clear — the mutations both
 * notes-agent's {@code ListManager} (LI-a) and memory-service's ambient capture (LI-b) compose.
 */
class MarkdownChecklistTest {

    @Test
    void parsesCheckedAndUncheckedItems() {
        MarkdownChecklist list = MarkdownChecklist.parse("- [ ] молоко\n- [x] яйца");
        assertThat(list.items()).hasSize(2);
        assertThat(list.items().get(0)).isEqualTo(new MarkdownChecklist.Item("молоко", false));
        assertThat(list.items().get(1)).isEqualTo(new MarkdownChecklist.Item("яйца", true));
    }

    @Test
    void parsesEmptyOrNullBodyToEmptyList() {
        assertThat(MarkdownChecklist.parse(null).isEmpty()).isTrue();
        assertThat(MarkdownChecklist.parse("").isEmpty()).isTrue();
        assertThat(MarkdownChecklist.parse("просто текст, не список").isEmpty()).isTrue();
    }

    @Test
    void addAppendsAnUncheckedItem() {
        MarkdownChecklist list = MarkdownChecklist.parse("- [ ] молоко").add("хлеб");
        assertThat(list.render()).isEqualTo("- [ ] молоко\n- [ ] хлеб");
    }

    @Test
    void addIsIdempotentCaseInsensitively() {
        MarkdownChecklist before = MarkdownChecklist.parse("- [ ] Молоко");
        MarkdownChecklist after = before.add("  молоко ");
        assertThat(after.render()).isEqualTo(before.render());
        assertThat(after.contains("МОЛОКО")).isTrue();
    }

    @Test
    void checkMarksMatchingItemDone() {
        MarkdownChecklist list = MarkdownChecklist.parse("- [ ] молоко\n- [ ] яйца").check("яйца");
        assertThat(list.render()).isEqualTo("- [ ] молоко\n- [x] яйца");
        assertThat(list.isChecked("яйца")).isTrue();
    }

    @Test
    void checkMissingItemIsNoOp() {
        MarkdownChecklist before = MarkdownChecklist.parse("- [ ] молоко");
        assertThat(before.check("хлеб").render()).isEqualTo(before.render());
    }

    @Test
    void clearRemovesEveryItem() {
        MarkdownChecklist list = MarkdownChecklist.parse("- [ ] молоко\n- [x] яйца").clear();
        assertThat(list.isEmpty()).isTrue();
        assertThat(list.render()).isEmpty();
    }

    @Test
    void addRejectsBlankItem() {
        MarkdownChecklist before = MarkdownChecklist.parse("- [ ] молоко");
        assertThat(before.add("   ").render()).isEqualTo(before.render());
    }
}
