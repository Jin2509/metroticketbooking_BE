package com.metroticket_api.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Một sự kiện bảo mật của tài khoản. Không bao giờ lưu mật khẩu, token hay mã OTP. */
@Entity
@Table(name = "auth_event")
public class AuthEvent {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuthEventType type;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthEvent() {
        // cho JPA
    }

    public AuthEvent(UUID userId, UUID actorId, AuthEventType type, String ipAddress, String userAgent, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.actorId = actorId;
        this.type = type;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getActorId() { return actorId; }
    public AuthEventType getType() { return type; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreatedAt() { return createdAt; }
}
