package dev.fedorov.ailife.memory.capture;

/**
 * The reconciliation decision for a near-duplicate note (ambient capture AC-5): what to do and, for an
 * {@link ReconcileAction#ENRICH}/{@link ReconcileAction#SUPERSEDE}, the full updated note body to write.
 * {@code body} is meaningless for {@link ReconcileAction#SKIP}.
 */
public record NoteReconciliation(ReconcileAction action, String body) {

    private static final NoteReconciliation SKIP = new NoteReconciliation(ReconcileAction.SKIP, null);

    /** The safe default — leave the existing note untouched. */
    public static NoteReconciliation skip() {
        return SKIP;
    }

    /** Whether this decision changes the existing note (an enrich/supersede with a usable body). */
    public boolean rewritesBody() {
        return action != ReconcileAction.SKIP && body != null && !body.isBlank();
    }
}
