package dev.fedorov.ailife.agents.tasks.read;

import dev.fedorov.ailife.agents.tasks.http.NextActionClient;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The tasks domain's <b>sharing-aware read</b> (ADR-0002 slice 5b). Every read flow that surfaces tasks —
 * today {@code next-action-suggester} — reads through here so the personal-vs-shared cut lives in exactly
 * one place, not copy-pasted per flow (the silent-drift failure the ADR exists to stop). The tasks sibling
 * of finance's {@code read/SpendingReads}.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #households(UUID, UUID, boolean)} — which household set a read spans. The <b>default</b>
 *       (own) cut is just the envelope household (the member's own tasks); the <b>shared</b> cut ("наши
 *       дела", "семейные задачи") is the member's personal ∪ shared set from profile-service, degrading to
 *       the envelope household when that set is empty (unknown user / profile down) so a read never
 *       breaks. Mirrors finance's own-by-default choice.</li>
 *   <li>{@link #nextActionsUnion(List, int)} — open next-actions read across the household set and flattened
 *       (capped at {@code limit}). A single-element set (the own cut) is identical to the pre-sharing
 *       single-household read; the LLM ranks the union, so a global re-sort here would be redundant.</li>
 * </ul>
 *
 * <p>mcp-tasks stays tenant-agnostic — it reads whatever single household it is handed. The
 * member→household-set resolution and the cross-household flatten live here, in the agent, mirroring
 * calendar-web's read path (PATTERNS "add sharing to a domain").
 */
@Component
public class TaskReads {

    private final NextActionClient nextActions;
    private final ProfileSharingClient profileSharing;

    public TaskReads(NextActionClient nextActions, ProfileSharingClient profileSharing) {
        this.nextActions = nextActions;
        this.profileSharing = profileSharing;
    }

    /**
     * The household set a read spans.
     *
     * @param envelope the requester's (own) household from the message envelope (non-null)
     * @param userId   the acting user, or {@code null} (then only the envelope household)
     * @param shared   {@code true} for the "наши дела" cut (personal ∪ shared); {@code false} → own only
     */
    public Mono<List<UUID>> households(UUID envelope, UUID userId, boolean shared) {
        if (!shared || userId == null) {
            return Mono.just(List.of(envelope));
        }
        return profileSharing.households(userId)
                .map(set -> set.isEmpty() ? List.of(envelope) : set);
    }

    /** Open next-actions unioned across {@code households} and flattened, capped at {@code limit}. */
    public Mono<List<TaskItemDto>> nextActionsUnion(List<UUID> households, int limit) {
        return Flux.fromIterable(households)
                .flatMap(h -> nextActions.fetchNextActions(h, limit))
                .collectList()
                .map(perHousehold -> perHousehold.stream()
                        .flatMap(List::stream)
                        .limit(limit)
                        .toList());
    }
}
