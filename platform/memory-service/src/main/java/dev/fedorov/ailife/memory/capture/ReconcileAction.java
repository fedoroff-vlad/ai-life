package dev.fedorov.ailife.memory.capture;

/**
 * What to do when a new note is a near-duplicate of an existing one (ambient capture AC-5,
 * plans/ambient-capture.md). A near-duplicate is not always a no-op:
 *
 * <ul>
 *   <li>{@link #ENRICH} — the new mention adds a detail the existing note is missing; fold it into the
 *       existing note's body.</li>
 *   <li>{@link #SUPERSEDE} — the new mention contradicts / updates the existing note ("передумал",
 *       "уже не …"); replace the stale content.</li>
 *   <li>{@link #SKIP} — nothing new; leave the existing note untouched (the AC-3 default).</li>
 * </ul>
 */
public enum ReconcileAction {
    ENRICH,
    SUPERSEDE,
    SKIP
}
