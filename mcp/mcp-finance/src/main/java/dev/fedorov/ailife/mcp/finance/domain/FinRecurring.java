package dev.fedorov.ailife.mcp.finance.domain;

import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "finance", name = "fin_recurring")
public class FinRecurring {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String cron;

    @Column(name = "next_due")
    private Instant nextDue;

    @Column
    private String note;

    @Column(name = "auto_remind", nullable = false)
    private boolean autoRemind;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinRecurring() {
    }

    public FinRecurring(UUID id, UUID householdId, UUID ownerId, UUID accountId,
                        UUID categoryId, String name, BigDecimal amount, String currency,
                        String cron, Instant nextDue, String note, boolean autoRemind) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.accountId = accountId;
        this.categoryId = categoryId;
        this.name = name;
        this.amount = amount;
        this.currency = currency;
        this.cron = cron;
        this.nextDue = nextDue;
        this.note = note;
        this.autoRemind = autoRemind;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getAccountId() { return accountId; }
    public UUID getCategoryId() { return categoryId; }
    public String getName() { return name; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCron() { return cron; }
    public Instant getNextDue() { return nextDue; }
    public String getNote() { return note; }
    public boolean isAutoRemind() { return autoRemind; }
    public UUID getScheduleId() { return scheduleId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public void setName(String name) { this.name = name; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setCron(String cron) { this.cron = cron; }
    public void setNextDue(Instant nextDue) { this.nextDue = nextDue; }
    public void setNote(String note) { this.note = note; }
    public void setAutoRemind(boolean autoRemind) { this.autoRemind = autoRemind; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }

    public FinRecurringDto toDto() {
        return new FinRecurringDto(id, householdId, ownerId, accountId, categoryId,
                name, amount, currency, cron, nextDue, note, autoRemind, scheduleId, createdAt);
    }
}
