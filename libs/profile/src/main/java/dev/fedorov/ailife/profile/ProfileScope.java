package dev.fedorov.ailife.profile;

import java.util.UUID;

/**
 * The one definition of the personalization <b>write scope</b> (ADR-0005): a member configures either
 * their <b>own</b> profile ({@code self} → {@code ownerId = userId}) or the <b>household-default</b> one
 * ({@code household} → {@code ownerId = null}). Five domains copy-pasted the same
 * {@code household ? null : userId} line (briefing/creator/nutrition/travel/stylist); this is its single
 * home. The scope string comes from the domain's {@code *-profiler} SKILL, which emits {@code "self"} or
 * {@code "household"}.
 */
public final class ProfileScope {

    /** The SKILL-emitted token that means "the shared household-default profile". Anything else is self. */
    public static final String HOUSEHOLD = "household";

    private ProfileScope() {
    }

    /** {@code true} when the scope selects the household-default profile (case-insensitive). */
    public static boolean isHousehold(String scope) {
        return HOUSEHOLD.equalsIgnoreCase(scope);
    }

    /**
     * The {@code owner_id} to write: {@code null} for a household-default profile, the speaker's
     * {@code userId} for their own. The single copy of the "self → the sender; household → the default"
     * rule the five profilers repeated.
     */
    public static UUID ownerId(String scope, UUID userId) {
        return isHousehold(scope) ? null : userId;
    }
}
