package dev.fedorov.ailife.mcp.finance.domain;

import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "finance", name = "fin_transaction")
public class FinTransaction {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Instant ts;

    @Column
    private String note;

    @Column(nullable = false)
    private String source = "manual";

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinTransaction() {
    }

    public FinTransaction(UUID id, UUID householdId, UUID accountId, UUID categoryId,
                          UUID ownerId, BigDecimal amount, String currency, Instant ts,
                          String note, String source, String externalRef) {
        this.id = id;
        this.householdId = householdId;
        this.accountId = accountId;
        this.categoryId = categoryId;
        this.ownerId = ownerId;
        this.amount = amount;
        this.currency = currency;
        this.ts = ts;
        this.note = note;
        if (source != null && !source.isBlank()) this.source = source;
        this.externalRef = externalRef;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getAccountId() { return accountId; }
    public UUID getCategoryId() { return categoryId; }
    public UUID getOwnerId() { return ownerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getTs() { return ts; }
    public String getNote() { return note; }
    public String getSource() { return source; }
    public String getExternalRef() { return externalRef; }
    public Instant getCreatedAt() { return createdAt; }

    public FinTransactionDto toDto() {
        return new FinTransactionDto(id, householdId, accountId, categoryId, ownerId,
                amount, currency, ts, note, source, externalRef, createdAt);
    }
}
