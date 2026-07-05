package dev.fedorov.ailife.scheduler.domain;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "core", name = "schedules")
public class Schedule {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_agent", nullable = false)
    private String ownerAgent;

    @Column(nullable = false)
    private String kind;

    @Column
    private String cron;

    @Column
    private String rrule;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "next_run_ts", nullable = false)
    private Instant nextRunTs;

    @Column(name = "last_run_ts")
    private Instant lastRunTs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Schedule() {
    }

    public Schedule(UUID id, UUID householdId, String ownerAgent, String kind,
                    String cron, JsonNode payload, Instant nextRunTs) {
        this.id = id;
        this.householdId = householdId;
        this.ownerAgent = ownerAgent;
        this.kind = kind;
        this.cron = cron;
        this.payload = payload;
        this.nextRunTs = nextRunTs;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getOwnerAgent() { return ownerAgent; }
    public String getKind() { return kind; }
    public String getCron() { return cron; }
    public String getRrule() { return rrule; }
    public JsonNode getPayload() { return payload; }
    public boolean isEnabled() { return enabled; }
    public Instant getNextRunTs() { return nextRunTs; }
    public Instant getLastRunTs() { return lastRunTs; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setNextRunTs(Instant nextRunTs) { this.nextRunTs = nextRunTs; }
    public void setLastRunTs(Instant lastRunTs) { this.lastRunTs = lastRunTs; }
}
