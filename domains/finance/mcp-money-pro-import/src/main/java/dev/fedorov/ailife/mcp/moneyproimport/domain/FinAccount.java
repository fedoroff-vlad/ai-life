package dev.fedorov.ailife.mcp.moneyproimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Second JPA view of {@code finance.fin_account} — owned by mcp-finance's
 * {@code 020-finance.yml}. Used to enforce the cross-household guard on every
 * {@code accountMap} entry, and (since the auto-create import mode) to insert a new
 * account for an unmapped Money Pro account name. Only the columns this module touches
 * are mapped; the rest ({@code owner_id}, {@code opening_balance}, {@code archived},
 * {@code metadata}, {@code created_at}) keep their DB defaults on insert.
 */
@Entity
@Table(schema = "finance", name = "fin_account")
public class FinAccount {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String currency;

    protected FinAccount() {
    }

    /** Creates a new account row (auto-create import mode). */
    public FinAccount(UUID id, UUID householdId, String name, String type, String currency) {
        this.id = id;
        this.householdId = householdId;
        this.name = name;
        this.type = type;
        this.currency = currency;
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getCurrency() { return currency; }
}
