package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;

import java.util.UUID;

/**
 * The outcome of a write-path resolution (ADR-0002 item 8, DS-N). Normally a create resolves straight to a
 * concrete household ({@link Resolved}); but when the default is <b>genuinely ambiguous</b> — the learned
 * tally is not confident <i>and</i> the domain policy abstains — the resolver returns {@link NeedsConfirm}
 * instead of silently guessing, so the caller can defer the write and ask the owner once (then finish via
 * {@link SharingResolver#confirm}). Callers that never want to ask stay on {@link SharingResolver#resolveHousehold},
 * which collapses {@code NeedsConfirm} to the fallback household.
 *
 * <p>The privacy boundary is unchanged: {@code NeedsConfirm} only means "don't default this one silently",
 * never a different household pick — the pick is still deterministic once a scope is known.
 */
public sealed interface SharingResolution {

    /** The household the item must be written to — the normal outcome. */
    record Resolved(UUID household) implements SharingResolution {}

    /**
     * The default is ambiguous: defer the write and ask the owner. Carries everything a resume needs to
     * finish and learn — the acting member's routing split (to pick the household once a scope is chosen),
     * the neutral {@link SharingContext} (to key the learn signal), and the envelope {@code fallbackHousehold}
     * (the safe degrade if the confirm is abandoned).
     */
    record NeedsConfirm(HouseholdRoutingDto routing, SharingContext ctx, UUID fallbackHousehold)
            implements SharingResolution {}
}
