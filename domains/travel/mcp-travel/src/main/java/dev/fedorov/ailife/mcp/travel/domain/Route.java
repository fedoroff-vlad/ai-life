package dev.fedorov.ailife.mcp.travel.domain;

import dev.fedorov.ailife.contracts.travel.RouteDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One imported route/itinerary (travel.md §Route import, #436). Household-scoped, optionally attached to a
 * {@code tripId}. {@code geometry} is the normalized JSON blob (track + waypoints), stored jsonb like
 * {@code travel_profile.rest_types}; {@code pointCount}/{@code distanceM} are derived at import for display.
 */
@Entity
@Table(schema = "travel", name = "route")
public class Route {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_format", nullable = false)
    private String sourceFormat;

    @Column(name = "point_count", nullable = false)
    private int pointCount;

    @Column(name = "distance_m")
    private BigDecimal distanceM;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode geometry;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected Route() {
    }

    public Route(UUID id, UUID householdId, UUID tripId, String name, String sourceFormat,
                 int pointCount, BigDecimal distanceM, JsonNode geometry) {
        this.id = id;
        this.householdId = householdId;
        this.tripId = tripId;
        this.name = name;
        this.sourceFormat = sourceFormat;
        this.pointCount = pointCount;
        this.distanceM = distanceM;
        this.geometry = geometry;
    }

    @PrePersist
    void onCreate() {
        if (importedAt == null) {
            importedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getTripId() { return tripId; }
    public String getName() { return name; }
    public String getSourceFormat() { return sourceFormat; }
    public int getPointCount() { return pointCount; }
    public BigDecimal getDistanceM() { return distanceM; }
    public JsonNode getGeometry() { return geometry; }
    public Instant getImportedAt() { return importedAt; }

    public RouteDto toDto() {
        return new RouteDto(id, householdId, tripId, name, sourceFormat, pointCount, distanceM,
                geometry, importedAt);
    }
}
