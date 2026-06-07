package dev.fedorov.ailife.mcp.moneyproimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Read-only second JPA view of {@code finance.fin_account} — owned by mcp-finance's
 * {@code 020-finance.yml}. We need it only to enforce the cross-household guard on
 * every entry in the caller's {@code accountMap} before any rows are inserted.
 */
@Entity
@Table(schema = "finance", name = "fin_account")
public class FinAccount {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private String currency;

    protected FinAccount() {
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getCurrency() { return currency; }
}
