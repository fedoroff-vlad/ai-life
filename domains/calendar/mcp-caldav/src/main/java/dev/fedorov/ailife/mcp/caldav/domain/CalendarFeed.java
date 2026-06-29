package dev.fedorov.ailife.mcp.caldav.domain;

import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA over {@code calendar.calendar_feed} — a read-only ICS feed token (#195). */
@Entity
@Table(schema = "calendar", name = "calendar_feed")
public class CalendarFeed {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CalendarFeed() {
    }

    public CalendarFeed(UUID id, UUID householdId, UUID ownerId, String token, String label, Instant createdAt) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.token = token;
        this.label = label;
        this.createdAt = createdAt;
    }

    public CalendarFeedDto toDto() {
        return new CalendarFeedDto(id, householdId, ownerId, label, token, createdAt, revokedAt);
    }

    public UUID id() { return id; }
    public UUID householdId() { return householdId; }
    public Instant revokedAt() { return revokedAt; }
    public void revoke(Instant at) { this.revokedAt = at; }
}
