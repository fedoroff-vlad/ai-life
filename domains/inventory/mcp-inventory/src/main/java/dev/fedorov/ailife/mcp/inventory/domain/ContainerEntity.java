package dev.fedorov.ailife.mcp.inventory.domain;

import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One container (inventory.container) — whatever carries a label: a box, a shelf, a bin, or a zone's
 * {@code loose} pseudo-container for open storage.
 *
 * {@code qrToken} is the printed identity and is minted once, at creation: it is deliberately NOT
 * derived from {@code code} or {@code label}, so renaming a box or moving it to another zone leaves
 * every label already stuck on it valid.
 */
@Entity
@Table(schema = "inventory", name = "container")
public class ContainerEntity {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(nullable = false)
    private String code;

    @Column
    private String label;

    @Column
    private String kind;

    @Column(name = "qr_token", nullable = false, updatable = false)
    private String qrToken;

    @Column(nullable = false)
    private String status;

    @Column
    private String destination;

    @Column
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected ContainerEntity() {
    }

    public ContainerEntity(UUID id, UUID householdId, UUID ownerId, String code, String qrToken) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.code = code;
        this.qrToken = qrToken;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "open";
        if (kind == null) kind = "box";
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getZoneId() { return zoneId; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getKind() { return kind; }
    public String getQrToken() { return qrToken; }
    public String getStatus() { return status; }
    public String getDestination() { return destination; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setZoneId(UUID zoneId) { this.zoneId = zoneId; }
    public void setCode(String code) { this.code = code; }
    public void setLabel(String label) { this.label = label; }
    public void setKind(String kind) { this.kind = kind; }
    public void setDestination(String destination) { this.destination = destination; }
    public void setNote(String note) { this.note = note; }

    /** Leaving {@code open} stamps {@code closedAt} — the packing session's end, used by the card. */
    public void setStatus(String status) {
        this.status = status;
        if (status != null && !"open".equals(status) && closedAt == null) {
            closedAt = Instant.now();
        }
    }

    public ContainerDto toDto() {
        return new ContainerDto(id, householdId, ownerId, zoneId, code, label, kind, qrToken,
                status, destination, note, createdAt, closedAt);
    }
}
