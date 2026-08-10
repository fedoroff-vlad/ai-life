package dev.fedorov.ailife.mcp.travel.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One travel-preferences row per person (household, owner) — home base + rest types + companions + budget hint. */
@Entity
@Table(schema = "travel", name = "travel_profile")
public class TravelProfile {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "home_base_label")
    private String homeBaseLabel;

    @Column(name = "home_base_latitude")
    private Double homeBaseLatitude;

    @Column(name = "home_base_longitude")
    private Double homeBaseLongitude;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rest_types", columnDefinition = "jsonb")
    private JsonNode restTypes;

    @Column
    private String companions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "child_ages", columnDefinition = "jsonb")
    private JsonNode childAges;

    @Column(name = "budget_amount")
    private BigDecimal budgetAmount;

    @Column(name = "budget_currency")
    private String budgetCurrency;

    @Column
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TravelProfile() {
    }

    public TravelProfile(UUID id, UUID householdId, UUID ownerId) {
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
    public String getHomeBaseLabel() { return homeBaseLabel; }
    public Double getHomeBaseLatitude() { return homeBaseLatitude; }
    public Double getHomeBaseLongitude() { return homeBaseLongitude; }
    public JsonNode getRestTypes() { return restTypes; }
    public String getCompanions() { return companions; }
    public JsonNode getChildAges() { return childAges; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public String getBudgetCurrency() { return budgetCurrency; }
    public String getNotes() { return notes; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setHomeBaseLabel(String homeBaseLabel) { this.homeBaseLabel = homeBaseLabel; }
    public void setHomeBaseLatitude(Double homeBaseLatitude) { this.homeBaseLatitude = homeBaseLatitude; }
    public void setHomeBaseLongitude(Double homeBaseLongitude) { this.homeBaseLongitude = homeBaseLongitude; }
    public void setRestTypes(JsonNode restTypes) { this.restTypes = restTypes; }
    public void setCompanions(String companions) { this.companions = companions; }
    public void setChildAges(JsonNode childAges) { this.childAges = childAges; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
    public void setBudgetCurrency(String budgetCurrency) { this.budgetCurrency = budgetCurrency; }
    public void setNotes(String notes) { this.notes = notes; }

    public TravelProfileDto toDto() {
        return new TravelProfileDto(id, householdId, ownerId, homeBaseLabel, homeBaseLatitude,
                homeBaseLongitude, restTypes, companions, childAges, budgetAmount, budgetCurrency,
                notes, updatedAt);
    }
}
