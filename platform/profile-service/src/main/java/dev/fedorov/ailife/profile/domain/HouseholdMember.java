package dev.fedorov.ailife.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's membership in a household (ADR-0001). Identity is 1:N: a user may be a member of their
 * personal household plus any shared (family) households they were approved into, and reads the
 * union over these memberships. {@code relationship} is set by the onboarding/approve flow (slice 3)
 * and is NULL for backfilled rows.
 */
@Entity
@Table(schema = "core", name = "household_members")
public class HouseholdMember {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String role;

    @Column
    private String relationship;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected HouseholdMember() {
    }

    public HouseholdMember(UUID id, UUID householdId, UUID userId, String role, String relationship) {
        this.id = id;
        this.householdId = householdId;
        this.userId = userId;
        this.role = role;
        this.relationship = relationship;
    }

    @PrePersist
    void ensureJoinedAt() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public String getRelationship() { return relationship; }
    public Instant getJoinedAt() { return joinedAt; }
}
