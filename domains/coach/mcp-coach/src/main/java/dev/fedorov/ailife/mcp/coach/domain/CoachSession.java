package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A coaching session envelope — observations/actions hang off it for continuity. */
@Entity
@Table(schema = "coach", name = "coach_session")
public class CoachSession {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @Column(nullable = false)
    private String mode;

    @Column
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoachSession() {
    }

    public CoachSession(UUID id, UUID householdId, UUID subject, String mode, String summary) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
        this.mode = mode;
        this.summary = summary;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getSubject() { return subject; }
    public String getMode() { return mode; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSummary(String summary) { this.summary = summary; }

    public CoachSessionDto toDto() {
        return new CoachSessionDto(id, householdId, subject, mode, summary, createdAt);
    }
}
