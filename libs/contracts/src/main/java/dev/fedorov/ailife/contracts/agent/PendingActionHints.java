package dev.fedorov.ailife.contracts.agent;

/**
 * Reserved keys the gateway reads from an agent's otherwise-opaque {@code pendingAction} envelope.
 * Kept here (in {@code contracts}) so the producer (the {@code agent-runtime} confirm-act runner) and
 * the consumer (gateway-telegram inline buttons, #489 RU-2) agree on the literal without either
 * module depending on the other.
 */
public final class PendingActionHints {

    /**
     * Marks a {@code pendingAction} as a <b>binary confirm</b> (да/нет). When {@code true} the gateway may
     * render a two-button "Да / Нет" inline keyboard whose tap maps back to exactly the affirmative /
     * negative text the user would otherwise type — routed to the agent's {@code /resume} through the
     * conversation route-lock, so the resume path is unchanged. Absent / {@code false} means the
     * {@code pendingAction} expects a free-text answer (a clarify, a "личное/общее?" sharing confirm) and
     * no keyboard is shown.
     */
    public static final String CONFIRM = "confirm";

    private PendingActionHints() {
    }
}
