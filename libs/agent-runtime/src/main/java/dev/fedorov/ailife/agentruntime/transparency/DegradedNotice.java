package dev.fedorov.ailife.agentruntime.transparency;

/**
 * Transparency helper (road-test <a href="https://github.com/fedoroff-vlad/ai-life/issues/485">#485</a>,
 * "no silent failures"): when a <b>best-effort</b> step on the reply path soft-fails (a render/store
 * hiccup, a memory write that didn't land, a tool that returned nothing), the reply should say so
 * discreetly instead of pretending success or returning silence. A personal assistant must be honest
 * about when it couldn't do the thing — silent degradation erodes daily trust.
 *
 * <p>{@link #append} folds a short, user-facing degraded-state note onto the reply text as a trailing
 * {@code ⚠️ …} line. The note is small per-flow copy (the caller owns the wording, in the user's
 * language); this primitive owns only the discreet, consistent rendering so every flow surfaces
 * degradation the same way. Pure, null/blank-safe, no Spring — callers use it inline in their
 * {@code onErrorResume}/soft-fail branch.
 */
public final class DegradedNotice {

    /** The discreet marker prefixing a degraded-state note (one glyph, so it reads as an aside). */
    public static final String MARKER = "⚠️";

    private DegradedNotice() {
    }

    /**
     * Append a discreet degraded-state note to a reply. A blank note leaves the text unchanged (nothing
     * degraded → nothing to say); a blank text yields the note alone. The note renders as a trailing
     * {@code "⚠️ <note>"} block separated from the reply by a blank line.
     */
    public static String append(String text, String note) {
        if (note == null || note.isBlank()) {
            return text == null ? "" : text;
        }
        String tail = MARKER + " " + note.strip();
        if (text == null || text.isBlank()) {
            return tail;
        }
        return text.strip() + "\n\n" + tail;
    }
}
