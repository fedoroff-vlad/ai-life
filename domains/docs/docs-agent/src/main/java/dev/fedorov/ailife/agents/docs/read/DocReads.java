package dev.fedorov.ailife.agents.docs.read;

import dev.fedorov.ailife.agents.docs.http.DocumentClient;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The docs domain's <b>sharing-aware read</b> (ADR-0002 slice 7b). The "find my X" flow ({@code DocFinder})
 * reads through here so the personal-vs-shared cut lives in exactly one place, not copy-pasted per search
 * source (the silent-drift failure the ADR exists to stop). The docs sibling of finance's
 * {@code read/SpendingReads}, tasks' {@code read/TaskReads} and nutrition's {@code read/MealReads}.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #households(UUID, UUID, boolean)} — which household set a search spans. The <b>default</b>
 *       (own) cut is just the envelope household (the member's own archive); the <b>shared</b> cut ("наши
 *       документы", "семейные документы") is the member's personal ∪ shared set from profile-service,
 *       degrading to the envelope household when that set is empty (unknown user / profile down) so a
 *       search never breaks. Mirrors finance/tasks/nutrition's own-by-default choice.</li>
 *   <li>{@link #searchUnion(List, String, String, int)} — the trigram search run across the set and
 *       flattened (capped at {@code limit}). A single-element set (the own cut) is identical to the
 *       pre-sharing single-household search.</li>
 * </ul>
 *
 * <p>mcp-docs stays tenant-agnostic — it searches whatever single household it is handed. The
 * member→household-set resolution and the cross-household flatten live here, in the agent, mirroring
 * calendar-web's read path (PATTERNS "add sharing to a domain").
 */
@Component
public class DocReads {

    private final DocumentClient documents;
    private final ProfileSharingClient profileSharing;

    public DocReads(DocumentClient documents, ProfileSharingClient profileSharing) {
        this.documents = documents;
        this.profileSharing = profileSharing;
    }

    /**
     * The household set a search spans.
     *
     * @param envelope the requester's (own) household from the message envelope (non-null)
     * @param userId   the acting user, or {@code null} (then only the envelope household)
     * @param shared   {@code true} for the "наши документы" cut (personal ∪ shared); {@code false} → own only
     */
    public Mono<List<UUID>> households(UUID envelope, UUID userId, boolean shared) {
        if (!shared || userId == null) {
            return Mono.just(List.of(envelope));
        }
        return profileSharing.households(userId)
                .map(set -> set.isEmpty() ? List.of(envelope) : set);
    }

    /** The trigram search run across {@code households} and flattened, capped at {@code limit}. */
    public Mono<List<DocumentDto>> searchUnion(List<UUID> households, String query, String docType, int limit) {
        return Flux.fromIterable(households)
                .flatMap(h -> documents.search(h, query, docType, limit)
                        .onErrorReturn(List.of()))
                .collectList()
                .map(perHousehold -> perHousehold.stream()
                        .flatMap(List::stream)
                        .limit(limit)
                        .toList());
    }
}
