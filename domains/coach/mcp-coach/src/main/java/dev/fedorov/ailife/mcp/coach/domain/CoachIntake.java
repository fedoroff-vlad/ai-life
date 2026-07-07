package dev.fedorov.ailife.mcp.coach.domain;

import dev.fedorov.ailife.contracts.coach.CoachIntakeDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A stored answer to a deliberate intake question; coach_value/coach_profile derive from these. */
@Entity
@Table(schema = "coach", name = "coach_intake")
public class CoachIntake {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private UUID subject;

    @Column
    private String topic;

    @Column(nullable = false)
    private String question;

    @Column
    private String answer;

    @Column(name = "asked_by", nullable = false)
    private String askedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoachIntake() {
    }

    public CoachIntake(UUID id, UUID householdId, UUID subject, String topic, String question,
                       String answer, String askedBy) {
        this.id = id;
        this.householdId = householdId;
        this.subject = subject;
        this.topic = topic;
        this.question = question;
        this.answer = answer;
        this.askedBy = askedBy;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getSubject() { return subject; }
    public String getTopic() { return topic; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getAskedBy() { return askedBy; }
    public Instant getCreatedAt() { return createdAt; }

    public CoachIntakeDto toDto() {
        return new CoachIntakeDto(id, householdId, subject, topic, question, answer, askedBy, createdAt);
    }
}
