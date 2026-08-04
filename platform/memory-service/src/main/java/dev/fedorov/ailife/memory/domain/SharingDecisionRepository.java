package dev.fedorov.ailife.memory.domain;

import dev.fedorov.ailife.contracts.common.SharingScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * JdbcTemplate over {@code memory.sharing_decision} (ADR-0002 item 8, DS-1) — the learned-decision tally
 * behind memory-driven default-sharing. Mirrors {@link NoteRepository} / {@link RelationRepository}: no JPA.
 * The store is deliberately dumb — it tallies opaque {@code signal_key}s and answers the per-signal counts;
 * the majority-vote judgement lives one layer up in the service.
 */
@Repository
public class SharingDecisionRepository {

    private final JdbcTemplate jdbc;

    public SharingDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Increment the tally for one resolved decision — insert the first occurrence, bump {@code count} +
     * {@code last_seen} on repeats. The unique {@code (household_id, domain, signal_key, scope)} constraint
     * makes this a clean upsert.
     */
    public void record(UUID householdId, String domain, String signalKey, SharingScope scope) {
        jdbc.update("""
                INSERT INTO memory.sharing_decision (household_id, domain, signal_key, scope)
                VALUES (?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_sharing_decision_signal
                DO UPDATE SET count = memory.sharing_decision.count + 1, last_seen = now()
                """, householdId, domain, signalKey, scope.name());
    }

    /**
     * The per-scope counts for one signal profile in one household+domain — at most one row per scope
     * (the unique constraint), so the map has 0–2 entries. Empty when the profile has never been seen.
     */
    public Map<SharingScope, Integer> counts(UUID householdId, String domain, String signalKey) {
        Map<SharingScope, Integer> tally = new EnumMap<>(SharingScope.class);
        jdbc.query("""
                SELECT scope, count FROM memory.sharing_decision
                 WHERE household_id = ? AND domain = ? AND signal_key = ?
                """,
                rs -> {
                    tally.put(SharingScope.valueOf(rs.getString("scope")), rs.getInt("count"));
                },
                householdId, domain, signalKey);
        return tally;
    }
}
