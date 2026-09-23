package dev.fedorov.ailife.mcp.inventory.domain;

import dev.fedorov.ailife.contracts.inventory.ItemDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored thing (inventory.item). The photo is the record — {@code mediaId} references the
 * media-service blob, bytes are never re-stored here. {@code title} + {@code description} are the
 * search corpus (filled from the vision caption in inventory-agent).
 */
@Entity
@Table(schema = "inventory", name = "item")
public class ItemEntity {

    @Id
    private UUID id;

    @Column(name = "container_id", nullable = false)
    private UUID containerId;

    @Column(name = "media_id", nullable = false)
    private String mediaId;

    @Column
    private String title;

    @Column
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode tags;

    @Column
    private Integer qty;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ItemEntity() {
    }

    public ItemEntity(UUID id, UUID containerId, String mediaId) {
        this.id = id;
        this.containerId = containerId;
        this.mediaId = mediaId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (qty == null) qty = 1;
    }

    public UUID getId() { return id; }
    public UUID getContainerId() { return containerId; }
    public String getMediaId() { return mediaId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public JsonNode getTags() { return tags; }
    public Integer getQty() { return qty; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setTags(JsonNode tags) { this.tags = tags; }
    public void setQty(Integer qty) { this.qty = qty; }

    public ItemDto toDto() {
        return new ItemDto(id, containerId, mediaId, title, description, tags, qty, createdAt);
    }
}
