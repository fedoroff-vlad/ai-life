package dev.fedorov.ailife.memory.capture;

/**
 * One note-worthy candidate pulled out of an ordinary message by {@link NoteWorthinessExtractor} — the
 * intuitive-capture counterpart of {@code FactExtractor}/{@code RelationExtractor} (plans/ambient-capture.md,
 * AC-1). It carries not just <i>what</i> to keep and <i>about whom</i>, but also how the system should act
 * on it, via {@link #outcome()}.
 *
 * @param title            a short, distinctive title the owner would use to find the note again, in the
 *                         message's language.
 * @param type             coarse note kind — one of {@code person|fact|idea|goal|journal|reflection}
 *                         (the note-manifest types). Free text; a downstream write defaults blanks to
 *                         {@code fact}.
 * @param body             the substance of the note, faithful to what was said.
 * @param subject          who the note is about: the literal {@code "self"} for the speaker, a person's
 *                         name as written, or {@code null} when it is about no specific person.
 * @param importance       how note-worthy the system judged this to be — {@code "important"} marks an
 *                         inferred fact worth keeping, anything else (or blank) is trivial. Only consulted
 *                         when there is no explicit fixation cue.
 * @param explicitFixation whether the message carried an explicit intent-to-record cue ("запомни",
 *                         "отметь", "зафиксируй", "не забудь", …).
 */
public record NoteCandidate(
        String title,
        String type,
        String body,
        String subject,
        String importance,
        boolean explicitFixation) {

    private static final String IMPORTANT = "important";

    /** Whether {@link #subject} names the speaker themselves. */
    public boolean isSelf() {
        return "self".equalsIgnoreCase(subject == null ? null : subject.trim());
    }

    /**
     * The three-way ambient-capture decision for this candidate. An explicit fixation cue always wins
     * (the owner asked); otherwise an {@code "important"} judgement means approve-first; everything else is
     * trivial and ignored.
     */
    public CaptureOutcome outcome() {
        if (explicitFixation) {
            return CaptureOutcome.EXPLICIT_FIXATION;
        }
        if (importance != null && IMPORTANT.equalsIgnoreCase(importance.trim())) {
            return CaptureOutcome.IMPORTANT_INFERRED;
        }
        return CaptureOutcome.TRIVIAL;
    }
}
