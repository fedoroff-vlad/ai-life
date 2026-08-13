package dev.fedorov.ailife.mcp.travel.domain;

import dev.fedorov.ailife.contracts.travel.TripExchangeDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An on-site currency swap during a trip (travel.md §Trip wallet, #437) — one paired op, both an
 * outflow of {@code fromCurrency} and an inflow of {@code toCurrency}. No stored rate (implied by the
 * two amounts).
 */
@Entity
@Table(schema = "travel", name = "trip_exchange")
public class TripExchange {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "from_currency", nullable = false)
    private String fromCurrency;

    @Column(name = "from_amount", nullable = false)
    private BigDecimal fromAmount;

    @Column(name = "to_currency", nullable = false)
    private String toCurrency;

    @Column(name = "to_amount", nullable = false)
    private BigDecimal toAmount;

    @Column(name = "exchanged_at", nullable = false)
    private Instant exchangedAt;

    @Column
    private String note;

    protected TripExchange() {
    }

    public TripExchange(UUID id, UUID tripId, String fromCurrency, BigDecimal fromAmount,
                        String toCurrency, BigDecimal toAmount, String note) {
        this.id = id;
        this.tripId = tripId;
        this.fromCurrency = fromCurrency;
        this.fromAmount = fromAmount;
        this.toCurrency = toCurrency;
        this.toAmount = toAmount;
        this.note = note;
    }

    @PrePersist
    void onCreate() {
        if (exchangedAt == null) exchangedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTripId() { return tripId; }
    public String getFromCurrency() { return fromCurrency; }
    public BigDecimal getFromAmount() { return fromAmount; }
    public String getToCurrency() { return toCurrency; }
    public BigDecimal getToAmount() { return toAmount; }
    public Instant getExchangedAt() { return exchangedAt; }
    public String getNote() { return note; }

    public TripExchangeDto toDto() {
        return new TripExchangeDto(id, tripId, fromCurrency, fromAmount, toCurrency, toAmount,
                exchangedAt, note);
    }
}
