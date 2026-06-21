package dev.fedorov.ailife.mcp.wardrobe.domain;

import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "wardrobe", name = "wardrobe_item")
public class WardrobeItem {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column
    private String category;

    @Column
    private String colour;

    @Column
    private String material;

    @Column
    private String pattern;

    @Column
    private String season;

    @Column
    private String formality;

    @Column(name = "image_media_id")
    private UUID imageMediaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WardrobeItem() {
    }

    public WardrobeItem(UUID id, UUID householdId, UUID ownerId, String name) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.name = name;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getColour() { return colour; }
    public String getMaterial() { return material; }
    public String getPattern() { return pattern; }
    public String getSeason() { return season; }
    public String getFormality() { return formality; }
    public UUID getImageMediaId() { return imageMediaId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setName(String name) { this.name = name; }
    public void setCategory(String category) { this.category = category; }
    public void setColour(String colour) { this.colour = colour; }
    public void setMaterial(String material) { this.material = material; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public void setSeason(String season) { this.season = season; }
    public void setFormality(String formality) { this.formality = formality; }
    public void setImageMediaId(UUID imageMediaId) { this.imageMediaId = imageMediaId; }

    public WardrobeItemDto toDto() {
        return new WardrobeItemDto(id, householdId, ownerId, name, category, colour, material,
                pattern, season, formality, imageMediaId, createdAt);
    }
}
