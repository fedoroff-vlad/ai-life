package dev.fedorov.ailife.agents.tasks.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

/**
 * Tasks' default-sharing rule (ADR-0002 slice 5): a task the author left unscoped defaults to the shared
 * household when it belongs on the <b>household / shared list</b> — a chore, a shared shopping item, or a
 * task that involves another household member ({@link SharingContext#involvesHouseholdMember()}) — and to
 * private (a personal todo) otherwise. This matches GTD's shared-list nature: a household list exists
 * precisely so every member sees it, while a personal next-action stays with its owner.
 *
 * <p>The routing mechanism itself lives in {@code libs/sharing}'s {@code SharingResolver}; this is the only
 * "what is shared here" logic tasks owns — the tasks sibling of calendar's {@code CalendarSharingPolicy}
 * and finance's {@code FinanceSharingPolicy}, plugged into the same {@link DefaultSharingPolicy} seam.
 * Deterministic today; the same interface can later be backed by a memory-driven default (ADR-0001 item 7)
 * without any change here or in the resolver.
 */
@Component
public class TasksSharingPolicy implements DefaultSharingPolicy {

    @Override
    public SharingScope decide(SharingContext ctx) {
        return ctx.involvesHouseholdMember() ? SharingScope.SHARED : SharingScope.PRIVATE;
    }
}
