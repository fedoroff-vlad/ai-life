package dev.fedorov.ailife.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "core", name = "users")
public class User {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String locale;

    @Column(name = "telegram_user_id", unique = true)
    private Long telegramUserId;

    @Column(nullable = false)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(UUID id, UUID householdId, String displayName, String locale,
                Long telegramUserId, String role) {
        this.id = id;
        this.householdId = householdId;
        this.displayName = displayName;
        this.locale = locale;
        this.telegramUserId = telegramUserId;
        this.role = role;
    }

    @PrePersist
    void ensureCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getHouseholdId() { return householdId; }
    public String getDisplayName() { return displayName; }
    public String getLocale() { return locale; }
    public Long getTelegramUserId() { return telegramUserId; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }

    public void updateDisplayName(String displayName) { this.displayName = displayName; }
    public void updateLocale(String locale) { this.locale = locale; }
    public void linkTelegram(Long telegramUserId) { this.telegramUserId = telegramUserId; }
}
