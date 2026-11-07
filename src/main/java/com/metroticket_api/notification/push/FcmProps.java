package com.metroticket_api.notification.push;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Firebase Cloud Messaging HTTP v1. serviceAccountJson: nội dung file JSON của service account (hoặc base64 của nó),
 * tạo ở Firebase Console → Project settings → Service accounts. Để trống = tắt push.
 */
@ConfigurationProperties(prefix = "app.push.fcm")
public record FcmProps(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String serviceAccountJson,
        @DefaultValue("https://fcm.googleapis.com") String apiBaseUrl,
        @DefaultValue("10s") Duration timeout) {

    public boolean configured() {
        return enabled && !serviceAccountJson.isBlank();
    }
}
