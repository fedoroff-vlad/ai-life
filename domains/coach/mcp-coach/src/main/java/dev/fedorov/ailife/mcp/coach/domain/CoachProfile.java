package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachProfileDto;
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

/** The per-subject coaching "vector" — one per (household, subject). */
@Entity
@Table(schema = "coach", name = "coach_profile")
public class CoachProfile {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "method_weights", columnDefinition = "jsonb")
    private JsonNode methodWeights;

    @Column
    private String tone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "focus_areas", columnDefinition = "jsonb")
    private JsonNode focusAreas;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode boundaries;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CoachProfile() {
    }

    public CoachProfile(UUID id, UUID householdId, UUID subject) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
    }

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getSubject() { return subject; }
    public JsonNode getMethodWeights() { return methodWeights; }
    public String getTone() { return tone; }
    public JsonNode getFocusAreas() { return focusAreas; }
    public JsonNode getBoundaries() { return boundaries; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMethodWeights(JsonNode methodWeights) { this.methodWeights = methodWeights; }
    public void setTone(String tone) { this.tone = tone; }
    public void setFocusAreas(JsonNode focusAreas) { this.focusAreas = focusAreas; }
    public void setBoundaries(JsonNode boundaries) { this.boundaries = boundaries; }
    public void setActive(boolean active) { this.active = active; }

    public CoachProfileDto toDto() {
        return new CoachProfileDto(id, householdId, subject, methodWeights, tone, focusAreas,
                boundaries, active, updatedAt);
    }
}
