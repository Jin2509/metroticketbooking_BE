package com.metroticket_api.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
        // cho JPA
    }

    public Notification(UUID userId, NotificationType type, String title, String body, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.createdAt = now;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public UUID getId() { return id; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
