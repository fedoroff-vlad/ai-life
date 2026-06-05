package dev.fedorov.ailife.mcp.icsimport.domain;

import dev.fedorov.ailife.contracts.calendar.IcsSubscriptionDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "calendar", name = "ics_subscriptions")
public class IcsSubscription {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String url;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IcsSubscription() {
    }

    public IcsSubscription(UUID id, UUID householdId, String name, String slug, String url) {
        this.id = id;
        this.householdId = householdId;
        this.name = name;
        this.slug = slug;
        this.url = url;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getUrl() { return url; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public String getLastError() { return lastError; }
    public UUID getScheduleId() { return scheduleId; }

    public void setUrl(String url) { this.url = url; }
    public void setName(String name) { this.name = name; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }

    public IcsSubscriptionDto toDto() {
        return new IcsSubscriptionDto(id, householdId, name, slug, url, lastSyncedAt, lastError);
    }
}
