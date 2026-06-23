package dev.fedorov.ailife.mcp.nutrition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One diet profile per person (household, owner) — goals + restrictions + tastes. */
@Entity
@Table(schema = "nutrition", name = "diet_profile")
public class DietProfile {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "goal_kcal")
    private Integer goalKcal;

    @Column(name = "goal_protein_g")
    private BigDecimal goalProteinG;

    @Column(name = "goal_fat_g")
    private BigDecimal goalFatG;

    @Column(name = "goal_carbs_g")
    private BigDecimal goalCarbsG;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode restrictions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode tastes;

    @Column
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DietProfile() {
    }

    public DietProfile(UUID id, UUID householdId, UUID ownerId) {
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
    public Integer getGoalKcal() { return goalKcal; }
    public BigDecimal getGoalProteinG() { return goalProteinG; }
    public BigDecimal getGoalFatG() { return goalFatG; }
    public BigDecimal getGoalCarbsG() { return goalCarbsG; }
    public JsonNode getRestrictions() { return restrictions; }
    public JsonNode getTastes() { return tastes; }
    public String getNotes() { return notes; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setGoalKcal(Integer goalKcal) { this.goalKcal = goalKcal; }
    public void setGoalProteinG(BigDecimal goalProteinG) { this.goalProteinG = goalProteinG; }
    public void setGoalFatG(BigDecimal goalFatG) { this.goalFatG = goalFatG; }
    public void setGoalCarbsG(BigDecimal goalCarbsG) { this.goalCarbsG = goalCarbsG; }
    public void setRestrictions(JsonNode restrictions) { this.restrictions = restrictions; }
    public void setTastes(JsonNode tastes) { this.tastes = tastes; }
    public void setNotes(String notes) { this.notes = notes; }

    public DietProfileDto toDto() {
        return new DietProfileDto(id, householdId, ownerId, goalKcal, goalProteinG, goalFatG,
                goalCarbsG, restrictions, tastes, notes, updatedAt);
    }
}
