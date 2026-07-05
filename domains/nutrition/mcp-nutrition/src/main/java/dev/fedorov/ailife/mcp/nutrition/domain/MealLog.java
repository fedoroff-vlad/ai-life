package dev.fedorov.ailife.mcp.nutrition.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One logged meal — a {@code nutrition.meal_log} row. */
@Entity
@Table(schema = "nutrition", name = "meal_log")
public class MealLog {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "eaten_at", nullable = false)
    private Instant eatenAt;

    @Column
    private String source;

    @Column(nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode items;

    @Column
    private Integer kcal;

    @Column(name = "protein_g")
    private BigDecimal proteinG;

    @Column(name = "fat_g")
    private BigDecimal fatG;

    @Column(name = "carbs_g")
    private BigDecimal carbsG;

    @Column(name = "image_media_id")
    private UUID imageMediaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MealLog() {
    }

    public MealLog(UUID id, UUID householdId, UUID ownerId, Instant eatenAt, String description) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.eatenAt = eatenAt;
        this.description = description;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public Instant getEatenAt() { return eatenAt; }
    public String getSource() { return source; }
    public String getDescription() { return description; }
    public JsonNode getItems() { return items; }
    public Integer getKcal() { return kcal; }
    public BigDecimal getProteinG() { return proteinG; }
    public BigDecimal getFatG() { return fatG; }
    public BigDecimal getCarbsG() { return carbsG; }
    public UUID getImageMediaId() { return imageMediaId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSource(String source) { this.source = source; }
    public void setItems(JsonNode items) { this.items = items; }
    public void setKcal(Integer kcal) { this.kcal = kcal; }
    public void setProteinG(BigDecimal proteinG) { this.proteinG = proteinG; }
    public void setFatG(BigDecimal fatG) { this.fatG = fatG; }
    public void setCarbsG(BigDecimal carbsG) { this.carbsG = carbsG; }
    public void setImageMediaId(UUID imageMediaId) { this.imageMediaId = imageMediaId; }

    public MealLogDto toDto() {
        return new MealLogDto(id, householdId, ownerId, eatenAt, source, description, items,
                kcal, proteinG, fatG, carbsG, imageMediaId, createdAt);
    }
}
