package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachHypothesisDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** A candidate recurring pattern — explicitly a hypothesis, revised as new data arrives. */
@Entity
@Table(schema = "coach", name = "coach_hypothesis")
public class CoachHypothesis {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private String status;

    @Column
    private Integer confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_observation_ids", columnDefinition = "jsonb")
    private JsonNode supportingObservationIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contradicting_observation_ids", columnDefinition = "jsonb")
    private JsonNode contradictingObservationIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CoachHypothesis() {
    }

    public CoachHypothesis(UUID id, UUID householdId, UUID subject, String text, String status,
                           Integer confidence, JsonNode supportingObservationIds,
                           JsonNode contradictingObservationIds) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
        this.text = text;
        this.status = status;
        this.confidence = confidence;
        this.supportingObservationIds = supportingObservationIds;
        this.contradictingObservationIds = contradictingObservationIds;
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
    public String getStatus() { return status; }
    public Integer getConfidence() { return confidence; }
    public JsonNode getSupportingObservationIds() { return supportingObservationIds; }
    public JsonNode getContradictingObservationIds() { return contradictingObservationIds; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public void setSupportingObservationIds(JsonNode ids) { this.supportingObservationIds = ids; }
    public void setContradictingObservationIds(JsonNode ids) { this.contradictingObservationIds = ids; }

    public CoachHypothesisDto toDto() {
        return new CoachHypothesisDto(id, householdId, subject, text, status, confidence,
                supportingObservationIds, contradictingObservationIds, createdAt, updatedAt);
    }
}
