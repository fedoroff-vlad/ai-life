package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachObservationDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** One grounded observation from a session; {@code method} is the move that produced it. */
@Entity
@Table(schema = "coach", name = "coach_observation")
public class CoachObservation {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private String method;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", columnDefinition = "jsonb")
    private JsonNode evidenceRefs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoachObservation() {
    }

    public CoachObservation(UUID id, UUID householdId, UUID subject, UUID sessionId,
                            String text, String method, JsonNode evidenceRefs) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
        this.sessionId = sessionId;
        this.text = text;
        this.method = method;
        this.evidenceRefs = evidenceRefs;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getSubject() { return subject; }
    public UUID getSessionId() { return sessionId; }
    public String getText() { return text; }
    public String getMethod() { return method; }
    public JsonNode getEvidenceRefs() { return evidenceRefs; }
    public Instant getCreatedAt() { return createdAt; }

    public CoachObservationDto toDto() {
        return new CoachObservationDto(id, householdId, subject, sessionId, text, method,
                evidenceRefs, createdAt);
    }
}
