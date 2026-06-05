package dev.fedorov.ailife.mcp.icsimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA view of calendar.events_cache for the ics-import side. Schema is owned by
 * 010-calendar.yml; this entity only sees the columns we need for external-sourced
 * rows (no etag handling, mirror is unidirectional).
 */
@Entity
@Table(schema = "calendar", name = "events_cache")
public class ExternalEvent {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "source_calendar", nullable = false)
    private String sourceCalendar;

    @Column(name = "calendar_uid", nullable = false)
    private String calendarUid;

    @Column
    private String etag;

    @Column(nullable = false)
    private String summary;

    @Column
    private String description;

    @Column
    private String location;

    @Column(nullable = false)
    private Instant dtstart;

    @Column
    private Instant dtend;

    @Column
    private String rrule;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> categories = new ArrayList<>();

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "raw_ics", columnDefinition = "text")
    private String rawIcs;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExternalEvent() {
    }

    public ExternalEvent(UUID id, UUID householdId, String sourceCalendar, String calendarUid, String summary) {
        this.id = id;
        this.householdId = householdId;
        this.sourceCalendar = sourceCalendar;
        this.calendarUid = calendarUid;
        this.summary = summary;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (lastSyncedAt == null) lastSyncedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        lastSyncedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getSourceCalendar() { return sourceCalendar; }
    public String getCalendarUid() { return calendarUid; }

    public void setEtag(String etag) { this.etag = etag; }
    public void setSummary(String summary) { this.summary = summary; }
    public void setDescription(String description) { this.description = description; }
    public void setLocation(String location) { this.location = location; }
    public void setDtstart(Instant dtstart) { this.dtstart = dtstart; }
    public void setDtend(Instant dtend) { this.dtend = dtend; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public void setCategories(List<String> categories) {
        this.categories = categories == null ? new ArrayList<>() : new ArrayList<>(categories);
    }
    public void setRawIcs(String rawIcs) { this.rawIcs = rawIcs; }
}
