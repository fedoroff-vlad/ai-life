package dev.fedorov.ailife.mcp.creator.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
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

/** One creator track per person (household, owner) — niche + audience + tone + platforms + guardrails. */
@Entity
@Table(schema = "creator", name = "creator_profile")
public class CreatorProfile {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column
    private String niche;

    @Column
    private String audience;

    @Column
    private String tone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode platforms;

    @Column
    private String goals;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode guardrails;

    @Column
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorProfile() {
    }

    public CreatorProfile(UUID id, UUID householdId, UUID ownerId) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
    }

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getNiche() { return niche; }
    public String getAudience() { return audience; }
    public String getTone() { return tone; }
    public JsonNode getPlatforms() { return platforms; }
    public String getGoals() { return goals; }
    public JsonNode getGuardrails() { return guardrails; }
    public String getNotes() { return notes; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setNiche(String niche) { this.niche = niche; }
    public void setAudience(String audience) { this.audience = audience; }
    public void setTone(String tone) { this.tone = tone; }
    public void setPlatforms(JsonNode platforms) { this.platforms = platforms; }
    public void setGoals(String goals) { this.goals = goals; }
    public void setGuardrails(JsonNode guardrails) { this.guardrails = guardrails; }
    public void setNotes(String notes) { this.notes = notes; }

    public CreatorProfileDto toDto() {
        return new CreatorProfileDto(id, householdId, ownerId, niche, audience, tone, platforms,
                goals, guardrails, notes, updatedAt);
    }
}
