package com.metroticket_api.notification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationView(UUID id, NotificationType type, String title, String body, boolean read,
            Instant readAt, Instant createdAt) {
        public static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getReadAt() != null,
                    n.getReadAt(), n.getCreatedAt());
        }
    }

    public record UnreadCountView(long count) {
    }

    /** Chọn đúng một trong hai: all = true, hoặc userIds. Bỏ trống all được hiểu là false. */
    public record SendNotificationRequest(
            Boolean all,
            @Size(max = 1000) Set<UUID> userIds,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String body) {
    }

    public record SendNotificationResult(int recipients) {
    }
}
