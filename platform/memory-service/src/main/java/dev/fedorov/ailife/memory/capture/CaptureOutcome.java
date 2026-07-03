package dev.fedorov.ailife.memory.capture;

/**
 * What ambient capture should do with a {@link NoteCandidate}. The three-way decision that turns the
 * second brain from "запомни …"-only into an intuitive one (plans/ambient-capture.md, AC-1):
 *
 * <ul>
 *   <li>{@link #EXPLICIT_FIXATION} — the message carried a fixation cue ("запомни", "отметь", …). The
 *       user asked, so it is written immediately with no approval.</li>
 *   <li>{@link #IMPORTANT_INFERRED} — the system judged it note-worthy on its own, no explicit cue. It is
 *       surfaced for the owner's approval before being saved (later phase, AC-4).</li>
 *   <li>{@link #TRIVIAL} — operational / small-talk / one-off. Nothing is written, nothing is asked.</li>
 * </ul>
 */
public enum CaptureOutcome {
    EXPLICIT_FIXATION,
    IMPORTANT_INFERRED,
    TRIVIAL
}
