package dev.fedorov.ailife.mcp.finance.domain;

import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "finance", name = "fin_account")
public class FinAccount {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String currency;

    @Column(name = "opening_balance", nullable = false)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinAccount() {
    }

    public FinAccount(UUID id, UUID householdId, UUID ownerId, String name,
                      String type, String currency, BigDecimal openingBalance) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.openingBalance = openingBalance == null ? BigDecimal.ZERO : openingBalance;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getCurrency() { return currency; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public boolean isArchived() { return archived; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setName(String name) { this.name = name; }
    public void setType(String type) { this.type = type; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance == null ? BigDecimal.ZERO : openingBalance;
    }
    public void setArchived(boolean archived) { this.archived = archived; }

    public FinAccountDto toDto() {
        return new FinAccountDto(id, householdId, ownerId, name, type, currency,
                openingBalance, archived, createdAt);
    }
}
