package dev.fedorov.ailife.agents.finance.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Finance's default-sharing rule (ADR-0002 slice 4b): an account the author left unscoped defaults to the
 * shared household when it is a <b>joint</b> account — one a household member co-owns
 * ({@link SharingContext#involvesHouseholdMember()}) — and to private (a personal card / cash) otherwise.
 * This matches the owner's account-level scoping decision: the account is the sharing boundary (joint →
 * shared, personal → private), and the default is the member's own (personal) household unless the account
 * reads as joint. The routing mechanism itself lives in {@code libs/sharing}'s {@code SharingResolver};
 * this is the only "what is shared here" logic finance owns.
 *
 * <p>The finance sibling of calendar's {@code CalendarSharingPolicy}, plugged into the same
 * {@link DefaultSharingPolicy} seam. Deterministic today; the same interface can later be backed by a
 * memory-driven default (ADR-0001 item 7) without any change here or in the resolver.
 *
 * <p><b>Confirm-on-ambiguity (item 8, DS-N).</b> When {@code AccountManager} couldn't classify the account —
 * the LLM gave no joint/personal signal at all — it marks the context with the {@code "account-unscoped"}
 * kind. {@link #maybeDecide} <b>abstains</b> on that kind (empty), so the resolver asks the owner "личное
 * или общее?" instead of silently defaulting to private — a wrong default here quietly hides a joint account
 * from the spouse (or exposes a personal card), the exact silent-wrong on a privacy boundary DS-N exists to
 * avoid. An account the LLM <i>did</i> classify carries the plain {@code "account"} kind and is answered
 * confidently, exactly as before. The sync {@link #decide} still returns a concrete safe default (private)
 * for any caller that never asks. Mirrors tasks' {@code TasksSharingPolicy}.
 */
@Component
public class FinanceSharingPolicy implements DefaultSharingPolicy {

    /** The {@link SharingContext#itemKind()} {@code AccountManager} sets when the LLM gave no scope signal. */
    public static final String UNSCOPED_KIND = "account-unscoped";

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
