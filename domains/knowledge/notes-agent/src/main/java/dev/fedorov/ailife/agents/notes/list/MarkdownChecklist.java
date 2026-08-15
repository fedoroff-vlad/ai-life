package dev.fedorov.ailife.agents.notes.list;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A list's items as a CommonMark task list — the body form of a {@code type=list}
 * {@code memory.note} (LI-a, {@link dev.fedorov.ailife.agents.notes.list}). One line per item:
 * <pre>
 * - [ ] молоко
 * - [x] яйца
 * </pre>
 * Pure + immutable: every mutation returns a new {@code MarkdownChecklist}, so the flow can render the
 * before/after body and decide whether anything actually changed. Item matching is case-insensitive on
 * the trimmed, whitespace-collapsed text. List notes are owned entirely by the lists capability, so
 * non-item lines in the body are not preserved on rewrite — the body is the checklist.
 */
public final class MarkdownChecklist {

    /** One checklist item: its display text and whether it is checked off (done). */
    public record Item(String text, boolean done) {
    }

    // - [ ] text  |  * [x] text  — leading whitespace tolerated, marker ' '/'x'/'X'.
    private static final Pattern LINE = Pattern.compile("^\\s*[-*]\\s+\\[( |x|X)]\\s+(.*\\S)\\s*$");

    private final List<Item> items;

    private MarkdownChecklist(List<Item> items) {
        this.items = List.copyOf(items);
    }

    /** Parse the checklist lines out of a note body; non-item lines are ignored. */
    public static MarkdownChecklist parse(String body) {
        List<Item> parsed = new ArrayList<>();
        if (body != null) {
            for (String line : body.split("\\r?\\n")) {
                Matcher m = LINE.matcher(line);
                if (m.matches()) {
                    boolean done = !m.group(1).equals(" ");
                    parsed.add(new Item(m.group(2).trim(), done));
                }
            }
        }
        return new MarkdownChecklist(parsed);
    }

    /** Render back to the {@code - [ ]}/{@code - [x]} body form (one item per line). */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("- [").append(it.done() ? 'x' : ' ').append("] ").append(it.text());
        }
        return sb.toString();
    }

    public List<Item> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** True if an item with this text already exists (case-insensitive, whitespace-insensitive). */
    public boolean contains(String item) {
        return indexOf(item) >= 0;
    }

    /** True if the matching item exists and is already checked off. */
    public boolean isChecked(String item) {
        int i = indexOf(item);
        return i >= 0 && items.get(i).done();
    }

    /** Append an unchecked item; a duplicate (case-insensitive) returns this unchanged. */
    public MarkdownChecklist add(String item) {
        String text = normalizeText(item);
        if (text == null || contains(text)) {
            return this;
        }
        List<Item> next = new ArrayList<>(items);
        next.add(new Item(text, false));
        return new MarkdownChecklist(next);
    }

    /** Mark the matching item done; a missing item returns this unchanged. */
    public MarkdownChecklist check(String item) {
        int i = indexOf(item);
        if (i < 0 || items.get(i).done()) {
            return this;
        }
        List<Item> next = new ArrayList<>(items);
        next.set(i, new Item(items.get(i).text(), true));
        return new MarkdownChecklist(next);
    }

    /** Drop every item (the note itself is kept by the caller). */
    public MarkdownChecklist clear() {
        return new MarkdownChecklist(List.of());
    }

    private int indexOf(String item) {
        String needle = normalizeKey(item);
        if (needle == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (normalizeKey(items.get(i).text()).equals(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeText(String item) {
        if (item == null) {
            return null;
        }
        String t = item.strip().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    private static String normalizeKey(String item) {
        String t = normalizeText(item);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }
}
