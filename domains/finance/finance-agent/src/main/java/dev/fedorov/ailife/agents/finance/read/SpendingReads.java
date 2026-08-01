package dev.fedorov.ailife.agents.finance.read;

import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The finance domain's shared <b>sharing-aware read</b> (ADR-0002 slice 4a). Every read flow that shows
 * "how much did I spend" — {@code financial-advisor}, {@code monthly-report}, {@code year-report} — reads
 * through here so the personal-vs-shared cut lives in exactly one place, not copy-pasted per flow (the
 * silent-drift failure the ADR exists to stop).
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #households(UUID, UUID, boolean)} — which household set a read spans. The <b>default</b>
 *       (personal) cut is just the envelope household (the member's personal household — the requester's
 *       own spending). The <b>shared</b> cut ("наши траты") is the member's personal ∪ shared set from
 *       profile-service, degrading to the envelope household when that set is empty (unknown user /
 *       profile down) so a read never breaks.</li>
 *   <li>{@link #spendingUnion(List, Instant, Instant)} — one spend-by-category window read across the
 *       household set and merged by category. A single-element set (the personal cut) is identical to the
 *       pre-sharing single-household read.</li>
 * </ul>
 *
 * <p>mcp-finance stays tenant-agnostic — it reads whatever single household it is handed. The
 * member→household-set resolution and the cross-household merge live here, in the agent, mirroring
 * calendar-web's read path (PATTERNS "add sharing to a domain").
 */
@Component
public class SpendingReads {

    private final SpendingClient spending;
    private final ProfileSharingClient profileSharing;

    public SpendingReads(SpendingClient spending, ProfileSharingClient profileSharing) {
        this.spending = spending;
        this.profileSharing = profileSharing;
    }

    /**
     * The household set a read spans.
     *
     * @param envelope the requester's (personal) household from the message envelope
     * @param userId   the acting user, or {@code null} (then only the envelope household)
     * @param shared   {@code true} for the "наши траты" cut (personal ∪ shared); {@code false} → own only
     */
    public Mono<List<UUID>> households(UUID envelope, UUID userId, boolean shared) {
        if (!shared || userId == null) {
            return Mono.just(List.of(envelope));
        }
        return profileSharing.households(userId)
                .map(set -> set.isEmpty() ? List.of(envelope) : set);
    }

    /** One spend-by-category window unioned across {@code households} and merged by category. */
    public Mono<List<SpendingByCategoryRow>> spendingUnion(List<UUID> households, Instant from, Instant to) {
        return Flux.fromIterable(households)
                .flatMap(h -> spending.spendingByCategory(h, from, to))
                .collectList()
                .map(SpendingReads::mergeByCategory);
    }

    /**
     * Sum spend-by-category rows across households by category — {@code spent} and {@code txCount} add up,
     * name/currency taken from the first row seen for a category. Deterministic (a privacy boundary, no
     * LLM); multi-currency categories keep the first currency (mcp-finance doesn't convert — see
     * {@link SpendingByCategoryRow}).
     */
    static List<SpendingByCategoryRow> mergeByCategory(List<List<SpendingByCategoryRow>> perHousehold) {
        Map<UUID, SpendingByCategoryRow> byCategory = new LinkedHashMap<>();
        for (List<SpendingByCategoryRow> rows : perHousehold) {
            for (SpendingByCategoryRow r : rows) {
                byCategory.merge(r.categoryId(), r, (a, b) -> new SpendingByCategoryRow(
                        a.categoryId(), a.categoryName(), a.currency(),
                        a.spent().add(b.spent()), a.txCount() + b.txCount()));
            }
        }
        return new ArrayList<>(byCategory.values());
    }
}
