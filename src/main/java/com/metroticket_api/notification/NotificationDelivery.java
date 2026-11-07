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

/** Một lần gửi push / email (outbox): gửi lại khi lỗi tạm thời, không mất khi restart. */
@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {

    public enum Channel { PUSH, EMAIL }

    public enum Status { PENDING, SENDING, SENT, FAILED, SKIPPED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "notification_id", nullable = false) private UUID notificationId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Channel channel;
    @Column(name = "device_id") private Long deviceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Status status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "locked_at") private Instant lockedAt;
    @Column(name = "last_error", length = 500) private String lastError;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected NotificationDelivery() {
    }

    public NotificationDelivery(UUID notificationId, UUID userId, Channel channel, Long deviceId, Instant now) {
        this.notificationId = notificationId;
        this.userId = userId;
        this.channel = channel;
        this.deviceId = deviceId;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void sent(Instant now) {
        status = Status.SENT;
        sentAt = now;
        lockedAt = null;
        lastError = null;
    }

    public void retry(String error, Instant next) {
        status = Status.PENDING;
        lastError = truncate(error);
        nextAttemptAt = next;
        lockedAt = null;
    }

    public void failed(String error) {
        status = Status.FAILED;
        lastError = truncate(error);
        lockedAt = null;
    }

    public void skipped(String reason) {
        status = Status.SKIPPED;
        lastError = truncate(reason);
        lockedAt = null;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    public Long getId() { return id; }
    public UUID getNotificationId() { return notificationId; }
    public UUID getUserId() { return userId; }
    public Channel getChannel() { return channel; }
    public Long getDeviceId() { return deviceId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}
