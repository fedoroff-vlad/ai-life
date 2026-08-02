package dev.fedorov.ailife.agents.nutritionist.read;

import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.agents.nutritionist.http.MealReadClient;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The nutrition domain's <b>sharing-aware read</b> (ADR-0002 slice 6b). The ration/shopping-list flow
 * ({@code MealPlanner}) reads through here so the personal-vs-shared cut lives in exactly one place, not
 * copy-pasted per flow (the silent-drift failure the ADR exists to stop). The nutrition sibling of
 * finance's {@code read/SpendingReads} and tasks' {@code read/TaskReads}.
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@link #households(UUID, UUID, boolean)} — which household set a read spans. The <b>default</b>
 *       (own) cut is just the envelope household (the member's own food log / a personal ration); the
 *       <b>shared</b> cut ("наш рацион", "на всю семью") is the member's personal ∪ shared set from
 *       profile-service, degrading to the envelope household when that set is empty (unknown user /
 *       profile down) so a read never breaks. Mirrors finance's own-by-default choice.</li>
 *   <li>{@link #householdProfiles(List)} — the household-default diet profiles across the set (a family
 *       ration needs every household's baseline; a 404/none-set profile is simply dropped).</li>
 *   <li>{@link #mealsUnion(List, UUID, int)} — recent meals read across the set and flattened (capped at
 *       {@code limit}). A single-element set (the own cut) is identical to the pre-sharing single-household
 *       read.</li>
 * </ul>
 *
 * <p>mcp-nutrition stays tenant-agnostic — it reads whatever single household it is handed. The
 * member→household-set resolution and the cross-household flatten live here, in the agent, mirroring
 * calendar-web's read path (PATTERNS "add sharing to a domain").
 */
@Component
public class MealReads {

    private final DietProfileClient profiles;
    private final MealReadClient meals;
    private final ProfileSharingClient profileSharing;

    public MealReads(DietProfileClient profiles, MealReadClient meals, ProfileSharingClient profileSharing) {
        this.profiles = profiles;
        this.meals = meals;
        this.profileSharing = profileSharing;
    }

    /**
     * The household set a read spans.
     *
     * @param envelope the requester's (own) household from the message envelope (non-null)
     * @param userId   the acting user, or {@code null} (then only the envelope household)
     * @param shared   {@code true} for the "наш рацион" cut (personal ∪ shared); {@code false} → own only
     */
    public Mono<List<UUID>> households(UUID envelope, UUID userId, boolean shared) {
        if (!shared || userId == null) {
            return Mono.just(List.of(envelope));
        }
        return profileSharing.households(userId)
                .map(set -> set.isEmpty() ? List.of(envelope) : set);
    }

    /** The household-default diet profile of each household in the set (none-set profiles dropped). */
    public Mono<List<DietProfileDto>> householdProfiles(List<UUID> households) {
        return Flux.fromIterable(households)
                .flatMap(h -> profiles.get(h, null))
                .collectList();
    }

    /** Recent meals unioned across {@code households} and flattened, capped at {@code limit}. */
    public Mono<List<MealLogDto>> mealsUnion(List<UUID> households, UUID ownerId, int limit) {
        return Flux.fromIterable(households)
                .flatMap(h -> meals.listMeals(h, ownerId, limit))
                .collectList()
                .map(perHousehold -> perHousehold.stream()
                        .flatMap(List::stream)
                        .limit(limit)
                        .toList());
    }
}
