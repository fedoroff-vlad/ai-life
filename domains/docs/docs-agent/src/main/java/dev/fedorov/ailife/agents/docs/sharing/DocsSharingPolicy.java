package dev.fedorov.ailife.agents.docs.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

/**
 * Documents' default-sharing rule (ADR-0002 slice 7): a <b>household asset</b> the author left unscoped
 * defaults to the shared household, while a personal paper stays private. The ADR frames the docs cut as
 * "household warranty/contract vs personal ID": a <b>warranty</b> (a fridge/TV appliance guarantee the
 * whole family relies on) and a <b>contract</b> (rent, utilities — household obligations) belong on the
 * family's shared surface so a spouse can find them too; everything else — a receipt, a sick-note, an ID
 * scan, an untyped doc — stays private to the sender.
 *
 * <p>The signal is the extracted {@code docType}, carried on {@link SharingContext#itemKind()} (the
 * nutrition sibling passes {@code "basket"} the same way). This is the <b>only</b> "what is shared here"
 * logic docs owns — the routing mechanism itself lives in {@code libs/sharing}'s {@code SharingResolver}
 * (a shared document still degrades to personal when the member has no family household yet), plugged into
 * the same {@link DefaultSharingPolicy} seam as {@code CalendarSharingPolicy} / {@code FinanceSharingPolicy}
 * / {@code TasksSharingPolicy} / {@code NutritionSharingPolicy}. Deterministic today; the same interface can
 * later be backed by a memory-driven default (ADR-0001 item 7) without any change here or in the resolver.
 */
@Component
public class DocsSharingPolicy implements DefaultSharingPolicy {

    @Override
    public SharingScope decide(SharingContext ctx) {
        String docType = ctx.itemKind() == null ? "" : ctx.itemKind().trim().toLowerCase();
        return switch (docType) {
            case "warranty", "contract" -> SharingScope.SHARED;
            default -> SharingScope.PRIVATE;
        };
    }
}
