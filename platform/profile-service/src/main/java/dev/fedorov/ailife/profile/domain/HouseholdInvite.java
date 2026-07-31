package dev.fedorov.ailife.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A pre-authorized invite into a shared (family) household (ADR-0001). The owner mints it (target
 * household + relationship + share-access); the invitee redeems it on first contact, which flips
 * {@code status} to {@code accepted}, records the invitee, and (when {@code grantSharedAccess})
 * inserts their {@link HouseholdMember} row into the family household.
 */
@Entity
@Table(schema = "core", name = "household_invites")
public class HouseholdInvite {

    public static final String PENDING = "pending";
    public static final String ACCEPTED = "accepted";
    public static final String REVOKED = "revoked";

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String token;

    @Column(name = "family_household_id", nullable = false, updatable = false)
    private UUID familyHouseholdId;

    @Column(name = "inviter_user_id", nullable = false, updatable = false)
    private UUID inviterUserId;

    @Column(updatable = false)
    private String relationship;

    @Column(name = "grant_shared_access", nullable = false, updatable = false)
    private boolean grantSharedAccess;

    @Column(nullable = false)
    private String status;

    @Column(name = "invitee_user_id")
    private UUID inviteeUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected HouseholdInvite() {
    }

    public HouseholdInvite(UUID id, String token, UUID familyHouseholdId, UUID inviterUserId,
                           String relationship, boolean grantSharedAccess) {
        this.id = id;
        this.token = token;
        this.familyHouseholdId = familyHouseholdId;
        this.inviterUserId = inviterUserId;
        this.relationship = relationship;
        this.grantSharedAccess = grantSharedAccess;
        this.status = PENDING;
    }

    @PrePersist
    void ensureCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Redeem this pending invite for the given user. Idempotency/state are enforced by the caller. */
    public void accept(UUID inviteeUserId) {
        this.status = ACCEPTED;
        this.inviteeUserId = inviteeUserId;
        this.acceptedAt = Instant.now();
    }

    public boolean isPending() { return PENDING.equals(status); }

    public UUID getId() { return id; }
    public String getToken() { return token; }
    public UUID getFamilyHouseholdId() { return familyHouseholdId; }
    public UUID getInviterUserId() { return inviterUserId; }
    public String getRelationship() { return relationship; }
    public boolean isGrantSharedAccess() { return grantSharedAccess; }
    public String getStatus() { return status; }
    public UUID getInviteeUserId() { return inviteeUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
