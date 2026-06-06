package dev.fedorov.ailife.mcp.finance.domain;

import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "finance", name = "fin_category")
public class FinCategory {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String kind;

    @Column
    private String icon;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinCategory() {
    }

    public FinCategory(UUID id, UUID householdId, UUID parentId, String name, String kind, String icon) {
        this.id = id;
        this.householdId = householdId;
        this.parentId = parentId;
        this.name = name;
        this.kind = kind;
        this.icon = icon;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getParentId() { return parentId; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getIcon() { return icon; }

    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public void setName(String name) { this.name = name; }
    public void setKind(String kind) { this.kind = kind; }
    public void setIcon(String icon) { this.icon = icon; }

    public FinCategoryDto toDto() {
        return new FinCategoryDto(id, householdId, parentId, name, kind, icon);
    }
}
