package dev.fedorov.ailife.mcp.docs.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One archived document (docs.document). Append-only: each ingest inserts a new row (unlike the
 * per-person profile stores, there is no upsert key — a household has many documents). The blob
 * itself lives in media-service; {@code mediaId} references it. {@code ocrText} is the full
 * recognised text and the search corpus.
 */
@Entity
@Table(schema = "docs", name = "document")
public class DocumentEntity {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "media_id", nullable = false)
    private String mediaId;

    @Column(name = "doc_type")
    private String docType;

    @Column
    private String title;

    @Column
    private String party;

    @Column(name = "doc_date")
    private LocalDate docDate;

    @Column
    private BigDecimal amount;

    @Column
    private String currency;

    @Column(name = "ocr_text")
    private String ocrText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode tags;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentEntity() {
    }

    public DocumentEntity(UUID id, UUID householdId, UUID ownerId, String mediaId) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.mediaId = mediaId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getMediaId() { return mediaId; }
    public String getDocType() { return docType; }
    public String getTitle() { return title; }
    public String getParty() { return party; }
    public LocalDate getDocDate() { return docDate; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getOcrText() { return ocrText; }
    public JsonNode getTags() { return tags; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDocType(String docType) { this.docType = docType; }
    public void setTitle(String title) { this.title = title; }
    public void setParty(String party) { this.party = party; }
    public void setDocDate(LocalDate docDate) { this.docDate = docDate; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setOcrText(String ocrText) { this.ocrText = ocrText; }
    public void setTags(JsonNode tags) { this.tags = tags; }

    public DocumentDto toDto() {
        return new DocumentDto(id, householdId, ownerId, mediaId, docType, title, party,
                docDate, amount, currency, ocrText, tags, createdAt);
    }
}
