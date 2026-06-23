package dev.fedorov.ailife.mcp.nutrition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One analysed grocery basket — a {@code nutrition.basket} row. {@code items} (line items) and
 * {@code analysis} (the breakdown) are jsonb; the line-item element shape is the contract's
 * {@code BasketItem} (the tool converts between it and the stored {@link JsonNode}).
 */
@Entity
@Table(schema = "nutrition", name = "basket")
public class Basket {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column
    private String merchant;

    @Column
    private String source;

    @Column(name = "receipt_media_id")
    private UUID receiptMediaId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode items;

    @Column
    private Integer kcal;

    @Column(name = "protein_g")
    private BigDecimal proteinG;

    @Column(name = "fat_g")
    private BigDecimal fatG;

    @Column(name = "carbs_g")
    private BigDecimal carbsG;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode analysis;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Basket() {
    }

    public Basket(UUID id, UUID householdId, UUID ownerId, Instant capturedAt) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.capturedAt = capturedAt;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public Instant getCapturedAt() { return capturedAt; }
    public String getMerchant() { return merchant; }
    public String getSource() { return source; }
    public UUID getReceiptMediaId() { return receiptMediaId; }
    public JsonNode getItems() { return items; }
    public Integer getKcal() { return kcal; }
    public BigDecimal getProteinG() { return proteinG; }
    public BigDecimal getFatG() { return fatG; }
    public BigDecimal getCarbsG() { return carbsG; }
    public JsonNode getAnalysis() { return analysis; }
    public Instant getCreatedAt() { return createdAt; }

    public void setMerchant(String merchant) { this.merchant = merchant; }
    public void setSource(String source) { this.source = source; }
    public void setReceiptMediaId(UUID receiptMediaId) { this.receiptMediaId = receiptMediaId; }
    public void setItems(JsonNode items) { this.items = items; }
    public void setKcal(Integer kcal) { this.kcal = kcal; }
    public void setProteinG(BigDecimal proteinG) { this.proteinG = proteinG; }
    public void setFatG(BigDecimal fatG) { this.fatG = fatG; }
    public void setCarbsG(BigDecimal carbsG) { this.carbsG = carbsG; }
    public void setAnalysis(JsonNode analysis) { this.analysis = analysis; }
}
