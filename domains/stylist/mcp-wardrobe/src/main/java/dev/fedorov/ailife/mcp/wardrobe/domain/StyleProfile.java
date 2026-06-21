package dev.fedorov.ailife.mcp.wardrobe.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
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

/** One style profile per person (household, owner) — the "analyse me" result. */
@Entity
@Table(schema = "wardrobe", name = "style_profile")
public class StyleProfile {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "person_type")
    private String personType;

    @Column(name = "body_shape")
    private String bodyShape;

    @Column(name = "colour_type")
    private String colourType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suitable_fabrics", columnDefinition = "jsonb")
    private JsonNode suitableFabrics;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurements", columnDefinition = "jsonb")
    private JsonNode measurements;

    @Column
    private String notes;

    @Column(name = "image_media_id")
    private UUID imageMediaId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StyleProfile() {
    }

    public StyleProfile(UUID id, UUID householdId, UUID ownerId) {
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
    public String getPersonType() { return personType; }
    public String getBodyShape() { return bodyShape; }
    public String getColourType() { return colourType; }
    public JsonNode getSuitableFabrics() { return suitableFabrics; }
    public Integer getHeightCm() { return heightCm; }
    public BigDecimal getWeightKg() { return weightKg; }
    public JsonNode getMeasurements() { return measurements; }
    public String getNotes() { return notes; }
    public UUID getImageMediaId() { return imageMediaId; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setPersonType(String personType) { this.personType = personType; }
    public void setBodyShape(String bodyShape) { this.bodyShape = bodyShape; }
    public void setColourType(String colourType) { this.colourType = colourType; }
    public void setSuitableFabrics(JsonNode suitableFabrics) { this.suitableFabrics = suitableFabrics; }
    public void setHeightCm(Integer heightCm) { this.heightCm = heightCm; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
    public void setMeasurements(JsonNode measurements) { this.measurements = measurements; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setImageMediaId(UUID imageMediaId) { this.imageMediaId = imageMediaId; }

    public StyleProfileDto toDto() {
        return new StyleProfileDto(id, householdId, ownerId, personType, bodyShape, colourType,
                suitableFabrics, heightCm, weightKg, measurements, notes, imageMediaId, updatedAt);
    }
}
