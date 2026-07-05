package dev.fedorov.ailife.mcp.briefing.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One briefing-preferences row per person (household, owner) — location + interests + sections + schedule. */
@Entity
@Table(schema = "briefing", name = "briefing_profile")
public class BriefingProfile {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "location_label")
    private String locationLabel;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode interests;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode sections;

    @Column(name = "schedule_time")
    private String scheduleTime;

    @Column(name = "schedule_enabled")
    private Boolean scheduleEnabled;

    /** Id of the auto-registered briefing.digest cron in scheduler-service (BR-f2); internal, not in the DTO. */
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BriefingProfile() {
    }

    public BriefingProfile(UUID id, UUID householdId, UUID ownerId) {
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
    public String getLocationLabel() { return locationLabel; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getTimezone() { return timezone; }
    public JsonNode getInterests() { return interests; }
    public JsonNode getSections() { return sections; }
    public String getScheduleTime() { return scheduleTime; }
    public Boolean getScheduleEnabled() { return scheduleEnabled; }
    public UUID getScheduleId() { return scheduleId; }
    public String getNotes() { return notes; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLocationLabel(String locationLabel) { this.locationLabel = locationLabel; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public void setInterests(JsonNode interests) { this.interests = interests; }
    public void setSections(JsonNode sections) { this.sections = sections; }
    public void setScheduleTime(String scheduleTime) { this.scheduleTime = scheduleTime; }
    public void setScheduleEnabled(Boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }
    public void setNotes(String notes) { this.notes = notes; }

    public BriefingProfileDto toDto() {
        return new BriefingProfileDto(id, householdId, ownerId, locationLabel, latitude, longitude,
                timezone, interests, sections, scheduleTime, scheduleEnabled, notes, updatedAt);
    }
}
