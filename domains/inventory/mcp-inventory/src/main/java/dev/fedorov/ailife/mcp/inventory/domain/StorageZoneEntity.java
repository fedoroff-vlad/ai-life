package dev.fedorov.ailife.mcp.inventory.domain;

import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One storage zone (inventory.storage_zone) — a physical place that holds containers. Upserted on
 * {@code (household_id, lower(name))}: the agent resolves a zone by the name the user said, so
 * "в кладовку" twice must land in one кладовка.
 */
@Entity
@Table(schema = "inventory", name = "storage_zone")
public class StorageZoneEntity {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column
    private String kind;

    /** Colour of the label stock this zone prints on — the printer is black-only (see the DTO). */
    @Column(name = "label_colour")
    private String labelColour;

    @Column
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StorageZoneEntity() {
    }

    public StorageZoneEntity(UUID id, UUID householdId, UUID ownerId, String name) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getLabelColour() { return labelColour; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setName(String name) { this.name = name; }
    public void setKind(String kind) { this.kind = kind; }
    public void setLabelColour(String labelColour) { this.labelColour = labelColour; }
    public void setNote(String note) { this.note = note; }

    public StorageZoneDto toDto() {
        return new StorageZoneDto(id, householdId, ownerId, name, kind, labelColour, note, createdAt);
    }
}
