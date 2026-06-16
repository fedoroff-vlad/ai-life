package dev.fedorov.ailife.mcp.tasks.domain;

import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "tasks", name = "task_item")
public class TaskItem {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String status;

    @Column
    private String context;

    @Column
    private Integer priority;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "defer_until")
    private Instant deferUntil;

    @Column
    private String note;

    @Column(nullable = false)
    private String source;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "calendar_event_uid")
    private String calendarEventUid;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TaskItem() {
    }

    public TaskItem(UUID id, UUID householdId, UUID ownerId, String title,
                    String status, String note, String source) {
        this.id = id;
        this.householdId = householdId;
        this.ownerId = ownerId;
        this.title = title;
        this.status = status;
        this.note = note;
        this.source = source;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getContext() { return context; }
    public Integer getPriority() { return priority; }
    public Instant getDueAt() { return dueAt; }
    public Instant getDeferUntil() { return deferUntil; }
    public String getNote() { return note; }
    public String getSource() { return source; }
    public String getExternalRef() { return externalRef; }
    public String getCalendarEventUid() { return calendarEventUid; }
    public UUID getScheduleId() { return scheduleId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public void setTitle(String title) { this.title = title; }
    public void setStatus(String status) { this.status = status; }
    public void setContext(String context) { this.context = context; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public void setDeferUntil(Instant deferUntil) { this.deferUntil = deferUntil; }
    public void setNote(String note) { this.note = note; }
    public void setCalendarEventUid(String calendarEventUid) { this.calendarEventUid = calendarEventUid; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public TaskItemDto toDto() {
        return new TaskItemDto(id, householdId, ownerId, projectId, title, status, context,
                priority, dueAt, deferUntil, note, source, externalRef, calendarEventUid,
                scheduleId, createdAt, completedAt);
    }
}
