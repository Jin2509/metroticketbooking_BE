package com.metroticket_api.chatbot;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chatbot_session")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "pending_place_resolution", columnDefinition = "TEXT")
    private String pendingPlaceResolution;

    @Column(name = "pending_place_expires_at")
    private Instant pendingPlaceExpiresAt;

    protected ChatSession() {
    }

    ChatSession(UUID userId, Instant now) {
        this.userId = userId;
        this.startedAt = now;
        this.lastMessageAt = now;
    }

    void touch(Instant now) {
        lastMessageAt = now;
    }

    void setPendingPlaceResolution(String value, Instant expiresAt) {
        pendingPlaceResolution = value;
        pendingPlaceExpiresAt = expiresAt;
    }

    void clearPendingPlaceResolution() {
        pendingPlaceResolution = null;
        pendingPlaceExpiresAt = null;
    }

    String getPendingPlaceResolution() {
        return pendingPlaceResolution;
    }

    Instant getPendingPlaceExpiresAt() {
        return pendingPlaceExpiresAt;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
}
