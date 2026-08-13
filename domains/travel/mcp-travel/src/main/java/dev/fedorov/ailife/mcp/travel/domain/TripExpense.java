package dev.fedorov.ailife.mcp.travel.domain;

import dev.fedorov.ailife.contracts.travel.TripExpenseDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A spend during a trip — an outflow of {@code currency} (travel.md §Trip wallet, #437). No
 * {@code paid_by} (settlement is cut).
 */
@Entity
@Table(schema = "travel", name = "trip_expense")
public class TripExpense {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column
    private String category;

    @Column
    private String description;

    @Column(name = "spent_at", nullable = false)
    private Instant spentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TripExpense() {
    }

    public TripExpense(UUID id, UUID tripId, String currency, BigDecimal amount,
                       String category, String description) {
        this.id = id;
        this.tripId = tripId;
        this.currency = currency;
        this.amount = amount;
        this.category = category;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (spentAt == null) spentAt = now;
        if (createdAt == null) createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTripId() { return tripId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public Instant getSpentAt() { return spentAt; }
    public Instant getCreatedAt() { return createdAt; }

    public TripExpenseDto toDto() {
        return new TripExpenseDto(id, tripId, currency, amount, category, description, spentAt, createdAt);
    }
}
