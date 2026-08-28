package dev.fedorov.ailife.profile;

import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The single home of the personalization <b>read-resolution</b> rule (ADR-0005), generalized from
 * briefing's {@code BriefingComposer.resolveProfile} (#490 FO-3). For a member reading a per-member
 * profile it applies, in order:
 * <ol>
 *   <li><b>self</b> — the member's own profile in the envelope household;</li>
 *   <li><b>own household-default</b> — the household's shared default ({@code ownerId = null});</li>
 *   <li><b>family (shared) household-default</b> — the shared default from any <i>other</i> household the
 *       member belongs to, so a new member who has set nothing inherits the family's default (FO-3). Only
 *       an explicitly-shared household-default is inherited — a member's own personal profile is never read
 *       for anyone else;</li>
 *   <li><b>empty</b> — nothing found: the caller applies its own domain default.</li>
 * </ol>
 *
 * <p>The domain supplies its typed {@code fetch(householdId, ownerId) -> Mono<T>} (its
 * {@link PersonalizationProfileClient#get}); the resolver owns the four-step fallback and the identity
 * read. The family step reuses {@link ProfileSharingClient#householdRouting} — one identity client, shared
 * with {@code libs/sharing} (ADR-0005 §Reuse the identity reads), so no second identity surface is invented.
 * A profile-service / MCP hiccup soft-fails the whole chain to {@link Mono#empty()} so the caller's own
 * default still applies (an error would otherwise bypass a downstream {@code switchIfEmpty(default)}).
 *
 * <p>Stateless — one shared bean serves every domain; the type is per call ({@code <T>}), so read-only web
 * services and agents alike reuse the single instance.
 */
public class ProfileScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(ProfileScopeResolver.class);

    private final ProfileSharingClient identity;

    public ProfileScopeResolver(ProfileSharingClient identity) {
        this.identity = identity;
    }

    /**
     * Resolve the profile a member should see: self → own household-default → family (shared)
     * household-default → empty. {@code fetch} is the domain's typed read {@code (householdId, ownerId) ->
     * Mono<T>}; an empty result at each step falls through to the next. Errors soft-fail to empty.
     */
    public <T> Mono<T> resolve(UUID userId, UUID householdId, BiFunction<UUID, UUID, Mono<T>> fetch) {
        return fetch.apply(householdId, userId)
                .switchIfEmpty(Mono.defer(() -> fetch.apply(householdId, null)))
                .switchIfEmpty(Mono.defer(() -> familyDefault(userId, householdId, fetch)))
                .onErrorResume(e -> {
                    log.warn("profile resolve failed, falling back to empty: {}", e.toString());
                    return Mono.empty();
                });
    }

    /**
     * The shared household-default from the caller's <i>other</i> (family) households (ADR-0001 tenant
     * routing). Resolves the member's personal/shared split, then returns the first shared household — other
     * than the envelope household the caller already tried — that has a household-default profile. Empty
     * (→ caller default) when the member has no family, no shared default, or profile-service is unreachable.
     */
    private <T> Mono<T> familyDefault(UUID userId, UUID householdId, BiFunction<UUID, UUID, Mono<T>> fetch) {
        if (userId == null) {
            return Mono.empty();
        }
        return identity.householdRouting(userId)
                .flatMapMany(routing -> Flux.fromIterable(routing.sharedHouseholdIds()))
                .filter(h -> !h.equals(householdId))
                .concatMap(h -> fetch.apply(h, null))
                .next()
                .onErrorResume(e -> {
                    log.warn("profile family-default resolve failed: {}", e.toString());
                    return Mono.empty();
                });
    }
}
