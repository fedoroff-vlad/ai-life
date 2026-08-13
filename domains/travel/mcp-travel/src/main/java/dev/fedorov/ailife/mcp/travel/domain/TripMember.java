package dev.fedorov.ailife.mcp.travel.domain;

import dev.fedorov.ailife.contracts.travel.TripMemberDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One participant on a trip roster (travel.md §Trip wallet, #437). At most one identity ref (userId or
 * personId) plus a required label; roster/context only — no share/weight, no settlement.
 */
@Entity
@Table(schema = "travel", name = "trip_member")
public class TripMember {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "person_id")
    private UUID personId;

    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TripMember() {
    }

    public TripMember(UUID id, UUID tripId, UUID userId, UUID personId, String label) {
        this.id = id;
        this.tripId = tripId;
        this.userId = userId;
        this.personId = personId;
        this.label = label;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTripId() { return tripId; }
    public UUID getUserId() { return userId; }
    public UUID getPersonId() { return personId; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }

    public TripMemberDto toDto() {
        return new TripMemberDto(id, tripId, userId, personId, label, createdAt);
    }
}
