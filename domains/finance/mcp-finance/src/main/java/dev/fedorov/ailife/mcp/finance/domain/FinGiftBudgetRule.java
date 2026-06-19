package dev.fedorov.ailife.mcp.finance.domain;

import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Relationship-tiered gift-budget rule (Stage 4 / Track D3) — one editable row
 * per (household, relationship tier). Upserted in place (no time-versioning,
 * unlike {@link FinBudget}: a budget preference has no audit need).
 */
@Entity
@Table(schema = "finance", name = "fin_gift_budget_rule")
public class FinGiftBudgetRule {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private String relationship;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinGiftBudgetRule() {
    }

    public FinGiftBudgetRule(UUID id, UUID householdId, String relationship,
                             BigDecimal amount, String currency) {
        this.id = id;
        this.householdId = householdId;
        this.relationship = relationship;
        this.amount = amount;
        this.currency = currency;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getRelationship() { return relationship; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }

    public GiftBudgetRuleDto toDto() {
        return new GiftBudgetRuleDto(id, householdId, relationship, amount, currency,
                createdAt, updatedAt);
    }
}
