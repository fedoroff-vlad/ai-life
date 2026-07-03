package dev.fedorov.ailife.memory.note;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code [[wiki-link]]} targets from a note's markdown body (second-brain
 * SB-3). One target per {@code [[…]]} token; an Obsidian-style alias
 * ({@code [[Target|display text]]}) keeps only the {@code Target} part. Targets are
 * trimmed, blanks dropped, and de-duplicated case-insensitively while preserving
 * first-seen order and casing — so a note that mentions {@code [[Мама]]} twice
 * produces a single edge.
 *
 * <p>Pure/stateless utility (no LLM, no I/O); the resolution of a target to a note or
 * a person happens in {@code NoteService}.
 */
public final class WikiLinkParser {

    /** {@code [[ … ]]} with no nested brackets inside. */
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\[\\]]+)]]");

    private WikiLinkParser() {
    }

    public static List<String> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Matcher m = LINK.matcher(body);
        // key = lowercased target (dedup), value = first-seen original casing (the label).
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        while (m.find()) {
            String raw = m.group(1);
            int pipe = raw.indexOf('|');
            String target = (pipe >= 0 ? raw.substring(0, pipe) : raw).trim();
            if (!target.isEmpty()) {
                seen.putIfAbsent(target.toLowerCase(Locale.ROOT), target);
            }
        }
        return List.copyOf(seen.values());
    }
}
