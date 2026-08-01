package dev.fedorov.ailife.agents.finance.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

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
 */
@Component
public class FinanceSharingPolicy implements DefaultSharingPolicy {

    @Override
    public SharingScope decide(SharingContext ctx) {
        return ctx.involvesHouseholdMember() ? SharingScope.SHARED : SharingScope.PRIVATE;
    }
}
