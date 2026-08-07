package dev.fedorov.ailife.agents.docs.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Documents' default-sharing rule (ADR-0002 slice 7): a <b>household asset</b> the author left unscoped
 * defaults to the shared household, while a personal paper stays private. The ADR frames the docs cut as
 * "household warranty/contract vs personal ID": a <b>warranty</b> (a fridge/TV appliance guarantee the
 * whole family relies on) and a <b>contract</b> (rent, utilities — household obligations) belong on the
 * family's shared surface so a spouse can find them too; a <b>receipt</b> or a <b>note</b> (a sick-note,
 * certificate, ID scan) stays private to the sender.
 *
 * <p>The signal is the extracted {@code docType}, carried on {@link SharingContext#itemKind()} (the
 * nutrition sibling passes {@code "basket"} the same way). This is the <b>only</b> "what is shared here"
 * logic docs owns — the routing mechanism itself lives in {@code libs/sharing}'s {@code SharingResolver}
 * (a shared document still degrades to personal when the member has no family household yet), plugged into
 * the same {@link DefaultSharingPolicy} seam as {@code CalendarSharingPolicy} / {@code FinanceSharingPolicy}
 * / {@code TasksSharingPolicy} / {@code NutritionSharingPolicy}. Deterministic today; the same interface can
 * later be backed by a memory-driven default (ADR-0001 item 7) without any change here or in the resolver.
 *
 * <p><b>Confirm-on-ambiguity (item 8, DS-N).</b> The {@code doc-archiver} SKILL types a document as
 * {@code receipt|contract|warranty|note|other}. The four named types are answered confidently above; but
 * {@code other} (or a blank/unreadable type) is genuinely ambiguous — the OCR/LLM couldn't tell what the
 * paper is, so a silent default (private) risks the exact wrong on a privacy boundary DS-N exists to avoid
 * (a poorly-OCR'd warranty quietly stays personal, so a spouse can't find it). {@link #maybeDecide}
 * <b>abstains</b> on {@code other}/unknown so the resolver asks "личное или общее?" instead of guessing.
 * The sync {@link #decide} still returns a concrete safe default (private) for callers that never ask.
 * Mirrors finance's {@code FinanceSharingPolicy} / tasks' {@code TasksSharingPolicy}.
 */
@Component
public class DocsSharingPolicy implements DefaultSharingPolicy {

    @Override
    public SharingScope decide(SharingContext ctx) {
        return switch (docType(ctx)) {
            case "warranty", "contract" -> SharingScope.SHARED;
            default -> SharingScope.PRIVATE;
        };
    }

    @Override
    public Optional<SharingScope> maybeDecide(SharingContext ctx) {
        return switch (docType(ctx)) {
            case "warranty", "contract" -> Optional.of(SharingScope.SHARED);
            case "receipt", "note" -> Optional.of(SharingScope.PRIVATE);
            // "other" / blank / unreadable → the OCR/LLM couldn't type it → let the resolver ask.
            default -> Optional.empty();
        };
    }

    private static String docType(SharingContext ctx) {
        return ctx.itemKind() == null ? "" : ctx.itemKind().trim().toLowerCase(Locale.ROOT);
    }
}
