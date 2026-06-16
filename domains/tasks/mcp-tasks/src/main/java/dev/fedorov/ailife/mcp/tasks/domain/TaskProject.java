package dev.fedorov.ailife.mcp.tasks.domain;

import dev.fedorov.ailife.contracts.tasks.TaskProjectDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "tasks", name = "task_project")
public class TaskProject {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    @Column
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskProject() {
    }

    public TaskProject(UUID id, UUID householdId, UUID ownerId, String name,
                       String status, String note) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.name = name;
        this.status = status;
        this.note = note;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setName(String name) { this.name = name; }
    public void setStatus(String status) { this.status = status; }
    public void setNote(String note) { this.note = note; }

    public TaskProjectDto toDto() {
        return new TaskProjectDto(id, householdId, ownerId, name, status, note, createdAt);
    }
}
