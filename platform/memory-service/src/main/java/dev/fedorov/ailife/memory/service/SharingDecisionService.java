package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import dev.fedorov.ailife.contracts.sharing.RecordSharingDecisionRequest;
import dev.fedorov.ailife.memory.domain.SharingDecisionRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Learned-decision tally behind memory-driven default-sharing (ADR-0002 item 8, DS-1). Records each resolved
 * sharing decision and answers the learned default for a signal profile as a <b>deterministic majority vote</b>
 * over the recorded counts — never LLM inference, because the sharing mechanism is a privacy boundary. The
 * caller (a {@code LearnedSharingPolicy} in {@code libs/sharing}, DS-2) compares {@code confidence}/{@code total}
 * against its own thresholds and falls back to the static per-domain policy when unconvinced or unseen.
 */
@Service
public class SharingDecisionService {

    private final SharingDecisionRepository repo;

    public SharingDecisionService(SharingDecisionRepository repo) {
        this.repo = repo;
    }

    /** Record one resolved decision into the tally. All fields required. */
    public void record(RecordSharingDecisionRequest req) {
        if (req == null || req.householdId() == null || req.scope() == null
                || isBlank(req.domain()) || isBlank(req.signalKey())) {
            throw new IllegalArgumentException(
                    "record requires householdId, domain, signalKey and scope");
        }
        repo.record(req.householdId(), req.domain().trim(), req.signalKey().trim(), req.scope());
    }

    /**
     * The learned default for a signal profile, or empty when it has never been recorded (→ the caller
     * uses its static policy). The winning scope is the most-recorded; ties break to {@link SharingScope#PRIVATE}
     * (the safer default) with confidence {@code 0.5}.
     */
    public Optional<LearnedSharingPolicyResponse> learnedPolicy(UUID householdId, String domain, String signalKey) {
        if (householdId == null || isBlank(domain) || isBlank(signalKey)) {
            throw new IllegalArgumentException("learnedPolicy requires householdId, domain and signalKey");
        }
        Map<SharingScope, Integer> counts = repo.counts(householdId, domain.trim(), signalKey.trim());
        if (counts.isEmpty()) {
            return Optional.empty();
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int shared = counts.getOrDefault(SharingScope.SHARED, 0);
        int privateCount = counts.getOrDefault(SharingScope.PRIVATE, 0);
        // Tie → PRIVATE, the safer privacy default; a low (0.5) confidence lets the caller reject the guess.
        SharingScope winner = shared > privateCount ? SharingScope.SHARED : SharingScope.PRIVATE;
        int winnerCount = Math.max(shared, privateCount);
        double confidence = total == 0 ? 0.0 : (double) winnerCount / total;
        return Optional.of(new LearnedSharingPolicyResponse(winner, confidence, total));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
