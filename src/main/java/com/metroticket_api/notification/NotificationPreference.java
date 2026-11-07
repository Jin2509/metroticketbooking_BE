package com.metroticket_api.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Tuỳ chọn nhận thông báo. Không có dòng = mặc định nhận hết. SECURITY luôn được gửi. */
@Entity
@Table(name = "notification_preference")
public class NotificationPreference {

    @Id @Column(name = "user_id") private UUID userId;
    @Column(name = "push_enabled", nullable = false) private boolean pushEnabled = true;
    @Column(name = "email_enabled", nullable = false) private boolean emailEnabled = true;
    @Column(name = "promotion_enabled", nullable = false) private boolean promotionEnabled = true;
    @Column(name = "reminder_enabled", nullable = false) private boolean reminderEnabled = true;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected NotificationPreference() {
    }

    public NotificationPreference(UUID userId, Instant now) {
        this.userId = userId;
        this.updatedAt = now;
    }

    public static NotificationPreference defaults(UUID userId) {
        return new NotificationPreference(userId, Instant.now());
    }

    /** Thông báo loại này có được tạo trong app không (nhắc nhở / khuyến mãi tắt được). */
    public boolean wants(NotificationType type) {
        if (type == NotificationType.PROMOTION) {
            return promotionEnabled;
        }
        if (type.isReminder()) {
            return reminderEnabled;
        }
        return true;
    }

    public boolean pushFor(NotificationType type) {
        return type == NotificationType.SECURITY || pushEnabled;
    }

    public boolean emailFor(NotificationType type) {
        return type == NotificationType.SECURITY || emailEnabled;
    }

    public UUID getUserId() { return userId; }
    public boolean isPushEnabled() { return pushEnabled; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public boolean isPromotionEnabled() { return promotionEnabled; }
    public boolean isReminderEnabled() { return reminderEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(Boolean push, Boolean email, Boolean promotion, Boolean reminder, Instant now) {
        if (push != null) pushEnabled = push;
        if (email != null) emailEnabled = email;
        if (promotion != null) promotionEnabled = promotion;
        if (reminder != null) reminderEnabled = reminder;
        updatedAt = now;
    }
}
