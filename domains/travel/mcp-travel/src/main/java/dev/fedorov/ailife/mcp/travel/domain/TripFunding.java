package dev.fedorov.ailife.mcp.travel.domain;

import dev.fedorov.ailife.contracts.travel.TripFundingDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A currency acquired externally for a trip — an inflow of {@code currency} (travel.md §Trip wallet, #437). */
@Entity
@Table(schema = "travel", name = "trip_funding")
public class TripFunding {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "rate_to_home")
    private BigDecimal rateToHome;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column
    private String note;

    protected TripFunding() {
    }

    public TripFunding(UUID id, UUID tripId, String currency, BigDecimal amount,
                       BigDecimal rateToHome, String note) {
        this.id = id;
        this.tripId = tripId;
        this.currency = currency;
        this.amount = amount;
        this.rateToHome = rateToHome;
        this.note = note;
    }

    @PrePersist
    void onCreate() {
        if (acquiredAt == null) acquiredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTripId() { return tripId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getRateToHome() { return rateToHome; }
    public Instant getAcquiredAt() { return acquiredAt; }
    public String getNote() { return note; }

    public TripFundingDto toDto() {
        return new TripFundingDto(id, tripId, currency, amount, rateToHome, acquiredAt, note);
    }
}
