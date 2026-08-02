package dev.fedorov.ailife.agents.nutritionist.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

/**
 * Nutrition's default-sharing rule (ADR-0002 slice 6): a <b>grocery basket</b> the author left unscoped
 * defaults to the shared household — buying groceries is a household-provisioning act, so a basket belongs
 * on the family's shared surface (the owner's decision: "семейная продуктовая корзина → shared household").
 * A basket that involves another household member ({@link SharingContext#involvesHouseholdMember()}) is
 * shared for the same reason. Everything else stays private.
 *
 * <p>The food log ({@code meal_log}) is inherently personal and never routes through here — only the
 * basket write path consults this policy — so a basket ({@code itemKind == "basket"}) is the one item that
 * defaults to shared. The routing mechanism itself lives in {@code libs/sharing}'s {@code SharingResolver}
 * (a shared basket still degrades to personal when the member has no family household yet); this is the only
 * "what is shared here" logic nutrition owns — the nutrition sibling of finance's {@code FinanceSharingPolicy}
 * and tasks' {@code TasksSharingPolicy}, plugged into the same {@link DefaultSharingPolicy} seam.
 * Deterministic today; the same interface can later be backed by a memory-driven default (ADR-0001 item 7)
 * without any change here or in the resolver.
 */
@Component
public class NutritionSharingPolicy implements DefaultSharingPolicy {

    @Override
    public SharingScope decide(SharingContext ctx) {
        return "basket".equals(ctx.itemKind()) || ctx.involvesHouseholdMember()
                ? SharingScope.SHARED : SharingScope.PRIVATE;
    }
}
