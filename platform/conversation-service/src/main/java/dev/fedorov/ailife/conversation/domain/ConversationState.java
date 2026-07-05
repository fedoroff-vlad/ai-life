package dev.fedorov.ailife.conversation.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One active control-state row per (household, user, channel) conversation. */
@Entity
@Table(schema = "core", name = "conversation_state")
public class ConversationState {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String channel;

    @Column(name = "route_lock")
    private String routeLock;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_action", columnDefinition = "jsonb")
    private JsonNode pendingAction;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationState() {
    }

    public ConversationState(UUID id, UUID householdId, UUID userId, String channel) {
        this.id = id;
        this.householdId = householdId;
        this.userId = userId;
        this.channel = channel;
    }

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public ConversationStateDto toDto() {
        return new ConversationStateDto(id, householdId, userId, channel,
                routeLock, pendingAction, expiresAt, updatedAt);
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getUserId() { return userId; }
    public String getChannel() { return channel; }
    public String getRouteLock() { return routeLock; }
    public void setRouteLock(String routeLock) { this.routeLock = routeLock; }
    public JsonNode getPendingAction() { return pendingAction; }
    public void setPendingAction(JsonNode pendingAction) { this.pendingAction = pendingAction; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
