package dev.fedorov.ailife.conversation.domain;

import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.contracts.conversation.SetConversationStateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the short-term conversation control state (route-lock + pending-action). One row per
 * (household, user, channel); {@link #set} upserts it, {@link #getActive} returns it only while
 * unexpired, {@link #clear} removes it (called once the awaiting agent resolves the pending action).
 *
 * <p>Expiry is enforced on read (a stale lock is treated as absent) rather than by a sweeper — a
 * forgotten confirmation simply ages out and the next message classifies normally. A future cleanup
 * job can hard-delete expired rows; not needed for correctness.
 */
@Service
public class ConversationStateService {

    /** Applied when a caller doesn't specify a TTL. 30 min — long enough for a reply, short enough not to trap. */
    static final long DEFAULT_TTL_SECONDS = 1800;

    private final ConversationStateRepository repo;

    public ConversationStateService(ConversationStateRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public ConversationStateDto set(SetConversationStateRequest req) {
        requireField(req.householdId(), "householdId");
        requireField(req.userId(), "userId");
        requireField(req.channel(), "channel");
        long ttl = req.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : req.ttlSeconds();

        ConversationState row = repo
                .findByHouseholdIdAndUserIdAndChannel(req.householdId(), req.userId(), req.channel())
                .orElseGet(() -> new ConversationState(
                        UUID.randomUUID(), req.householdId(), req.userId(), req.channel()));
        row.setRouteLock(req.routeLock());
        row.setPendingAction(req.pendingAction());
        row.setExpiresAt(Instant.now().plusSeconds(ttl));
        return repo.save(row).toDto();
    }

    @Transactional(readOnly = true)
    public Optional<ConversationStateDto> getActive(UUID householdId, UUID userId, String channel) {
        return repo.findByHouseholdIdAndUserIdAndChannel(householdId, userId, channel)
                .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .map(ConversationState::toDto);
    }

    @Transactional
    public void clear(UUID householdId, UUID userId, String channel) {
        repo.findByHouseholdIdAndUserIdAndChannel(householdId, userId, channel)
                .ifPresent(repo::delete);
    }

    private static void requireField(Object value, String name) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
