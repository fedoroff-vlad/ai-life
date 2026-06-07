package dev.fedorov.ailife.mcp.finance.domain;

import dev.fedorov.ailife.contracts.finance.FinBudgetDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "finance", name = "fin_budget")
public class FinBudget {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(nullable = false)
    private String period;

    @Column(name = "limit_amount", nullable = false)
    private BigDecimal limitAmount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinBudget() {
    }

    public FinBudget(UUID id, UUID householdId, UUID categoryId, String period,
                     BigDecimal limitAmount, String currency, Instant validFrom) {
        this.id = id;
        this.householdId = householdId;
        this.categoryId = categoryId;
        this.period = period;
        this.limitAmount = limitAmount;
        this.currency = currency;
        this.validFrom = validFrom == null ? Instant.now() : validFrom;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getCategoryId() { return categoryId; }
    public String getPeriod() { return period; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public String getCurrency() { return currency; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidTo() { return validTo; }
    public UUID getScheduleId() { return scheduleId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setValidTo(Instant validTo) { this.validTo = validTo; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }

    public FinBudgetDto toDto() {
        return new FinBudgetDto(id, householdId, categoryId, period,
                limitAmount, currency, validFrom, validTo, scheduleId);
    }
}
