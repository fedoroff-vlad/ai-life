package dev.fedorov.ailife.profile.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
@Table(schema = "core", name = "people")
public class Person {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column
    private String relationship;

    @Column
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode interests;

    @Column
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lead_days_override", columnDefinition = "jsonb")
    private JsonNode leadDaysOverride;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Person() {
    }

    public Person(UUID id, UUID householdId, String displayName, String relationship,
                  String locale, JsonNode interests, String notes, JsonNode leadDaysOverride) {
        this.id = id;
        this.householdId = householdId;
        this.displayName = displayName;
        this.relationship = relationship;
        this.locale = locale;
        this.interests = interests;
        this.notes = notes;
        this.leadDaysOverride = leadDaysOverride;
    }

    @PrePersist
    void ensureCreatedAt() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getDisplayName() { return displayName; }
    public String getRelationship() { return relationship; }
    public String getLocale() { return locale; }
    public JsonNode getInterests() { return interests; }
    public String getNotes() { return notes; }
    public JsonNode getLeadDaysOverride() { return leadDaysOverride; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setRelationship(String relationship) { this.relationship = relationship; }
    public void setLocale(String locale) { this.locale = locale; }
    public void setInterests(JsonNode interests) { this.interests = interests; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setLeadDaysOverride(JsonNode leadDaysOverride) { this.leadDaysOverride = leadDaysOverride; }
}
