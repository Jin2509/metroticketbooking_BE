package com.metroticket_api.notification;

import java.util.List;
import java.util.UUID;

/** Phát sau khi tạo thông báo; listener AFTER_COMMIT đẩy realtime qua SSE. */
public record NotificationsCreatedEvent(List<UUID> notificationIds, UUID campaignId) {

    public static NotificationsCreatedEvent single(UUID notificationId) {
        return new NotificationsCreatedEvent(List.of(notificationId), null);
    }

    public static NotificationsCreatedEvent campaign(UUID campaignId) {
        return new NotificationsCreatedEvent(List.of(), campaignId);
    }
}
