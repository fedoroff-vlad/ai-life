package dev.fedorov.ailife.mcp.creator.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.creator.ContentPieceDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One generated content piece (idea | draft) — a {@code creator.content_piece} row. */
@Entity
@Table(schema = "creator", name = "content_piece")
public class ContentPiece {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private String kind;

    @Column
    private String platform;

    @Column
    private String title;

    @Column
    private String body;

    @Column
    private String cta;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode hashtags;

    @Column(nullable = false)
    private String status;

    @Column(name = "trend_id")
    private UUID trendId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContentPiece() {
    }

    public ContentPiece(UUID id, UUID householdId, UUID ownerId, String kind) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.kind = kind;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getKind() { return kind; }
    public String getPlatform() { return platform; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getCta() { return cta; }
    public JsonNode getHashtags() { return hashtags; }
    public String getStatus() { return status; }
    public UUID getTrendId() { return trendId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setPlatform(String platform) { this.platform = platform; }
    public void setTitle(String title) { this.title = title; }
    public void setBody(String body) { this.body = body; }
    public void setCta(String cta) { this.cta = cta; }
    public void setHashtags(JsonNode hashtags) { this.hashtags = hashtags; }
    public void setStatus(String status) { this.status = status; }
    public void setTrendId(UUID trendId) { this.trendId = trendId; }

    public ContentPieceDto toDto() {
        return new ContentPieceDto(id, householdId, ownerId, kind, platform, title, body, cta,
                hashtags, status, trendId, createdAt);
    }
}
