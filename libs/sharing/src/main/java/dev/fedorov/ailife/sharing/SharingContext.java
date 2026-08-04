package dev.fedorov.ailife.sharing;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The neutral signals a {@link DefaultSharingPolicy} reads to decide the default privacy of an item when
 * the author made no explicit {@link dev.fedorov.ailife.contracts.common.SharingScope} choice (ADR-0002).
 *
 * <p>Deliberately minimal — start with the signals calendar already used and grow a field only when a new
 * domain reveals one it genuinely needs (the ADR flags this record as an evolving shared contract):
 * <ul>
 *   <li>{@code categories} — free-form tags/categories on the item (calendar: {@code birthday},
 *       {@code anniversary}, …; finance: an account or category label). Never {@code null} — an absent
 *       list becomes empty.</li>
 *   <li>{@code involvesHouseholdMember} — whether another household member is a party to the item
 *       (e.g. a task assigned to a spouse, a joint-account transaction). A hint some policies weight
 *       toward shared.</li>
 *   <li>{@code itemKind} — an optional domain-specific discriminator (e.g. {@code "meal-plan"} vs
 *       {@code "food-log"}) when categories alone don't separate shared from private.</li>
 * </ul>
 *
 * <p>The <b>mechanism</b> that consumes a policy's verdict ({@link SharingResolver}) is deterministic — a
 * privacy boundary, never LLM-decided. Only the {@code decide} that reads this context is where judgement
 * (and, later, memory-driven learning) lives, all behind the {@link DefaultSharingPolicy} seam.
 */
public record SharingContext(List<String> categories, boolean involvesHouseholdMember, String itemKind) {

    public SharingContext {
        categories = categories != null ? List.copyOf(categories) : List.of();
    }

    /** An item carrying only category tags — the common calendar/finance shape. */
    public static SharingContext ofCategories(List<String> categories) {
        return new SharingContext(categories, false, null);
    }

    /** True if any category equals {@code tag}, case-insensitively (trimmed). Policy helper. */
    public boolean hasCategory(String tag) {
        if (tag == null) {
            return false;
        }
        for (String c : categories) {
            if (c != null && c.trim().equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A stable, low-cardinality digest of these signals — the key the learned-decision tally is grouped by
     * (ADR-0002 item 8). Two contexts with the same normalised signals map to the same key, so the same kind
     * of item accumulates one tally. Categories are trimmed, lower-cased, de-duped and <b>sorted</b> so order
     * never splits the key. Deterministic and side-effect-free.
     */
    public String signalKey() {
        List<String> cats = categories.stream()
                .filter(Objects::nonNull)
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .distinct()
                .sorted()
                .toList();
        String kind = itemKind == null ? "" : itemKind.trim().toLowerCase(Locale.ROOT);
        return "cat=" + String.join(",", cats) + "|kind=" + kind + "|member=" + involvesHouseholdMember;
    }
}
