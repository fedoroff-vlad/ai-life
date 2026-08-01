package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The deterministic <b>write-path</b> engine of the sharing capability (ADR-0002): resolve which concrete
 * {@code household_id} a new item must be written to. This is the single home of the rule set that used to
 * live inline in calendar-agent — every domain now shares it and adds only its {@link DefaultSharingPolicy}.
 *
 * <p>The rule, in order:
 * <ol>
 *   <li>An explicit {@link SharingScope} on the create-input wins; otherwise the domain's
 *       {@link DefaultSharingPolicy#decide(SharingContext)} chooses the default.</li>
 *   <li>With no {@code userId} (the pre-membership inter-agent path), route to the caller-supplied
 *       {@code fallbackHousehold} (the envelope household) unchanged.</li>
 *   <li>Otherwise ask profile-service for the routing split and pick: {@code SHARED} → the first shared
 *       household when one exists, else personal; {@code PRIVATE} → personal. A shared choice with no
 *       family household <b>degrades to personal</b> — the single-owner reality where no shared space
 *       exists yet.</li>
 *   <li>If profile-service can't resolve the user (404 → empty routing), fall back to
 *       {@code fallbackHousehold}.</li>
 * </ol>
 *
 * <p>The mechanism is deterministic on purpose — it is a privacy boundary. Only step 1's default (behind
 * the policy seam) is judgement; the household pick and fallbacks never guess.
 */
public class SharingResolver {

    private final ProfileSharingClient profile;
    private final DefaultSharingPolicy policy;

    public SharingResolver(ProfileSharingClient profile, DefaultSharingPolicy policy) {
        this.profile = profile;
        this.policy = policy;
    }

    /**
     * Resolve the concrete household to write an item into.
     *
     * @param userId            the acting user, or {@code null} on the pre-membership inter-agent path
     * @param explicitScope     the author's explicit choice, or {@code null} to apply the domain policy
     * @param ctx               the neutral signals the policy reads when {@code explicitScope} is absent
     * @param fallbackHousehold the envelope household to use when there is no {@code userId} or the user
     *                          can't be resolved (may be {@code null} → {@link Mono#empty()})
     * @return the resolved {@code household_id}, or {@link Mono#empty()} when nothing resolves
     */
    public Mono<UUID> resolveHousehold(UUID userId, SharingScope explicitScope, SharingContext ctx,
                                       UUID fallbackHousehold) {
        SharingScope scope = explicitScope != null ? explicitScope : policy.decide(ctx);
        if (userId == null) {
            return Mono.justOrEmpty(fallbackHousehold);
        }
        return profile.householdRouting(userId)
                .flatMap(routing -> Mono.justOrEmpty(pickHousehold(routing, scope)))
                .switchIfEmpty(Mono.justOrEmpty(fallbackHousehold));
    }

    /**
     * SHARED → the first family household when the user has one, else personal; PRIVATE → personal.
     * A shared choice with no family household degrades to personal.
     */
    static UUID pickHousehold(HouseholdRoutingDto routing, SharingScope scope) {
        List<UUID> shared = routing.sharedHouseholdIds();
        if (scope == SharingScope.SHARED && shared != null && !shared.isEmpty()) {
            return shared.get(0);
        }
        return routing.personalHouseholdId();
    }
}
