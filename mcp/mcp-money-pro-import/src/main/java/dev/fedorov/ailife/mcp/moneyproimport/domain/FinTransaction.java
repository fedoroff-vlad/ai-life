package dev.fedorov.ailife.mcp.moneyproimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Write-side JPA view of {@code finance.fin_transaction} — owned by mcp-finance's
 * {@code 020-finance.yml}. We only ever insert; mcp-finance owns reads, updates and
 * deletes. The DB unique on {@code (household_id, source, external_ref)} is what
 * makes Money Pro re-imports idempotent.
 */
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
}
