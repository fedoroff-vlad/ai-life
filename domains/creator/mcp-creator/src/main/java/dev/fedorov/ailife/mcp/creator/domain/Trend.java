package dev.fedorov.ailife.mcp.creator.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.creator.TrendDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One cached trend — a {@code creator.trend} row. */
@Entity
@Table(schema = "creator", name = "trend")
public class Trend {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column
    private String source;

    @Column
    private String platform;

    @Column(nullable = false)
    private String title;

    @Column
    private String url;

    @Column
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode metrics;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Trend() {
    }

    public Trend(UUID id, UUID householdId, UUID ownerId, Instant capturedAt, String title) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.capturedAt = capturedAt;
        this.title = title;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getSource() { return source; }
    public String getPlatform() { return platform; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public String getSummary() { return summary; }
    public JsonNode getMetrics() { return metrics; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSource(String source) { this.source = source; }
    public void setPlatform(String platform) { this.platform = platform; }
    public void setUrl(String url) { this.url = url; }
    public void setSummary(String summary) { this.summary = summary; }
    public void setMetrics(JsonNode metrics) { this.metrics = metrics; }

    public TrendDto toDto() {
        return new TrendDto(id, householdId, ownerId, source, platform, title, url, summary,
                metrics, capturedAt, createdAt);
    }
}
