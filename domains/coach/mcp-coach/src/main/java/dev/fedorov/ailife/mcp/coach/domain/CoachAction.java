package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachActionDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A proposed next step — the Develop-mode follow-up spine. */
@Entity
@Table(schema = "coach", name = "coach_action")
public class CoachAction {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @Column(nullable = false)
    private String text;

    @Column(name = "value_id")
    private UUID valueId;

    @Column(name = "hypothesis_id")
    private UUID hypothesisId;

    @Column(nullable = false)
    private String status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CoachAction() {
    }

    public CoachAction(UUID id, UUID householdId, UUID subject, String text, UUID valueId,
                       UUID hypothesisId, String status, Instant dueAt) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
        this.text = text;
        this.valueId = valueId;
        this.hypothesisId = hypothesisId;
        this.status = status;
        this.dueAt = dueAt;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getSubject() { return subject; }
    public String getText() { return text; }
    public UUID getValueId() { return valueId; }
    public UUID getHypothesisId() { return hypothesisId; }
    public String getStatus() { return status; }
    public Instant getDueAt() { return dueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }

    public CoachActionDto toDto() {
        return new CoachActionDto(id, householdId, subject, text, valueId, hypothesisId, status,
                dueAt, createdAt, updatedAt);
    }
}
