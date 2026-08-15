package dev.fedorov.ailife.memory.capture;

/**
 * One everyday-list add intent pulled out of an ordinary message by {@link ListIntentExtractor} — the
 * lists counterpart of {@link NoteCandidate} for ambient / intuitive capture (plans/lists.md §LI-b, riding
 * plans/ambient-capture.md). Unlike a note candidate there is no three-way outcome: the extractor only
 * emits a candidate when it judged the message a genuine "put this on a list" intent, so a candidate
 * <i>is</i> the decision to add. The write posture (auto-save + notify) is applied downstream in LI-b2.
 *
 * @param item the single thing to put on the list, in the message's language, lightly cleaned
 *             ("молоко", "хлеб", "milk"). Always present (a blank-item candidate is dropped).
 * @param list the list the speaker means, as named or implied ("список покупок", "shopping list",
 *             "что взять в поездку"), or {@code null} when none was named — a downstream write defaults a
 *             null/blank list to the everyday shopping list.
 */
public record ListItemCandidate(String item, String list) {
}
