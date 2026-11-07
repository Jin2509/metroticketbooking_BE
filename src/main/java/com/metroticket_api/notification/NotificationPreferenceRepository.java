package com.metroticket_api.notification;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    default NotificationPreference of(UUID userId) {
        return findById(userId).orElseGet(() -> NotificationPreference.defaults(userId));
    }
}
