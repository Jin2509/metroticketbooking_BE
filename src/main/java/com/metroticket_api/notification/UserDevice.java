package com.metroticket_api.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Thiết bị đăng ký nhận push (FCM registration token). */
@Entity
@Table(name = "user_device")
public class UserDevice {

    public enum Platform { ANDROID, IOS, WEB }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, unique = true, length = 512) private String token;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Platform platform;
    @Column(name = "app_version", length = 40) private String appVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
    @Column(name = "disabled_at") private Instant disabledAt;

    protected UserDevice() {
    }

    public UserDevice(UUID userId, String token, Platform platform, String appVersion, Instant now) {
        this.userId = userId;
        this.token = token;
        this.platform = platform;
        this.appVersion = appVersion;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /** Token có thể chuyển sang tài khoản khác khi đăng nhập tài khoản khác trên cùng máy. */
    public void refresh(UUID userId, Platform platform, String appVersion, Instant now) {
        this.userId = userId;
        this.platform = platform;
        this.appVersion = appVersion;
        this.lastSeenAt = now;
        this.disabledAt = null;
    }

    public void disable(Instant now) {
        disabledAt = now;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getToken() { return token; }
    public Platform getPlatform() { return platform; }
    public String getAppVersion() { return appVersion; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getDisabledAt() { return disabledAt; }
}
