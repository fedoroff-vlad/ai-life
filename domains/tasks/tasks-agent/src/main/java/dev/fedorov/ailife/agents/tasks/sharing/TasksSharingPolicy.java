package dev.fedorov.ailife.agents.tasks.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
 *
 * <p><b>Confirm-on-ambiguity (item 8, DS-N).</b> When {@code TaskCapturer} couldn't classify the capture —
 * the LLM gave no shared/personal signal at all — it marks the context with the {@code "task-unscoped"} kind.
 * {@link #maybeDecide} <b>abstains</b> on that kind (empty), so the resolver asks the owner "личное или
 * общее?" instead of silently defaulting to private. A capture the LLM <i>did</i> classify carries the plain
 * {@code "task"} kind and is answered confidently, exactly as before. The sync {@link #decide} still returns a
 * concrete safe default (private) for any caller that never asks.
 */
@Component
public class TasksSharingPolicy implements DefaultSharingPolicy {

    /** The {@link SharingContext#itemKind()} {@code TaskCapturer} sets when the LLM gave no scope signal. */
    public static final String UNSCOPED_KIND = "task-unscoped";

    @Override
    public SharingScope decide(SharingContext ctx) {
        return ctx.involvesHouseholdMember() ? SharingScope.SHARED : SharingScope.PRIVATE;
    }

    @Override
    public Optional<SharingScope> maybeDecide(SharingContext ctx) {
        if (UNSCOPED_KIND.equals(ctx.itemKind())) {
            return Optional.empty(); // genuinely ambiguous → let the resolver ask
        }
        return Optional.of(decide(ctx));
    }
}
