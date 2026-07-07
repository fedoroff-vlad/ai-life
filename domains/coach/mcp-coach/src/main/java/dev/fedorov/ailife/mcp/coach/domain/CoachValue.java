package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachValueDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A value the subject holds — subject-owned, editable. */
@Entity
@Table(schema = "coach", name = "coach_value")
public class CoachValue {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @Column(nullable = false)
    private String label;

    @Column
    private String note;

    @Column(nullable = false)
    private String source;

    @Column
    private Integer weight;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoachValue() {
    }

    public CoachValue(UUID id, UUID householdId, UUID subject, String label, String note,
                      String source, Integer weight) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
        this.label = label;
        this.note = note;
        this.source = source;
        this.weight = weight;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getSubject() { return subject; }
    public String getLabel() { return label; }
    public String getNote() { return note; }
    public String getSource() { return source; }
    public Integer getWeight() { return weight; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void setLabel(String label) { this.label = label; }
    public void setNote(String note) { this.note = note; }
    public void setSource(String source) { this.source = source; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public void setActive(boolean active) { this.active = active; }

    public CoachValueDto toDto() {
        return new CoachValueDto(id, householdId, subject, label, note, source, weight, active, createdAt);
    }
}
