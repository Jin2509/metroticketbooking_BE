package com.metroticket_api.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.notification.NotificationDelivery.Channel;
import com.metroticket_api.notification.push.PushSender;

/**
 * Điểm vào duy nhất để gửi thông báo cho MỘT người (thông báo tự động, thông báo nghiệp vụ).
 *
 * <ol>
 *   <li>Áp tuỳ chọn của người nhận: tắt khuyến mãi / nhắc nhở thì không tạo; SECURITY luôn gửi.</li>
 *   <li>dedupeKey: mỗi (người nhận, khoá) chỉ tạo một lần, chặn ở DB bằng ON CONFLICT DO NOTHING.</li>
 *   <li>Lưu thông báo trong app (cùng transaction với nghiệp vụ: nghiệp vụ rollback thì không có thông báo).</li>
 *   <li>Xếp hàng push (mỗi thiết bị một bản ghi) và email vào outbox, DeliveryWorker gửi ở nền.</li>
 *   <li>Sau commit đẩy realtime qua SSE cho các tab/app đang mở.</li>
 * </ol>
 */
@Service
public class NotificationDispatcher {

    static final Pattern LINK = Pattern.compile("^/[A-Za-z0-9/_\\-?=&.%]*$");

    public record Request(UUID userId, NotificationType type, String title, String body, NotificationPriority priority,
            String link, String dedupeKey, boolean push, boolean email) {

        public static Request of(UUID userId, NotificationType type, String title, String body) {
            return new Request(userId, type, title, body, NotificationPriority.NORMAL, null, null, true, false);
        }

        public Request link(String value) {
            return new Request(userId, type, title, body, priority, value, dedupeKey, push, email);
        }

        public Request dedupe(String key) {
            return new Request(userId, type, title, body, priority, link, key, push, email);
        }

        public Request withEmail() {
            return new Request(userId, type, title, body, priority, link, dedupeKey, push, true);
        }

        public Request high() {
            return new Request(userId, type, title, body, NotificationPriority.HIGH, link, dedupeKey, push, email);
        }
    }

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final UserDeviceRepository devices;
    private final NotificationDeliveryRepository deliveries;
    private final PushSender pushSender;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public NotificationDispatcher(NotificationRepository notifications, NotificationPreferenceRepository preferences,
            UserDeviceRepository devices, NotificationDeliveryRepository deliveries, PushSender pushSender,
            ApplicationEventPublisher events, Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.devices = devices;
        this.deliveries = deliveries;
        this.pushSender = pushSender;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Optional<Notification> dispatch(Request r) {
        NotificationPreference pref = preferences.of(r.userId());
        if (!pref.wants(r.type())) {
            return Optional.empty();
        }
        String link = r.link() != null && LINK.matcher(r.link()).matches() ? r.link() : null;
        String title = truncate(r.title(), 200);
        String body = truncate(r.body(), 2000);
        Instant now = clock.instant();
        Notification n;
        if (r.dedupeKey() != null) {
            UUID id = UUID.randomUUID();
            int inserted = notifications.insertIfAbsent(id, r.userId(), r.type().name(), title, body, now,
                    r.priority().name(), link, truncate(r.dedupeKey(), 150));
            if (inserted == 0) {
                return Optional.empty();
            }
            n = notifications.findById(id).orElseThrow();
        } else {
            n = notifications.save(new Notification(r.userId(), r.type(), title, body, now, r.priority(), link, null));
        }
        if (r.push() && pref.pushFor(r.type()) && pushSender.available()) {
            for (UserDevice device : devices.findByUserIdAndDisabledAtIsNull(r.userId())) {
                deliveries.save(new NotificationDelivery(n.getId(), r.userId(), Channel.PUSH, device.getId(), now));
            }
        }
        if (r.email() && pref.emailFor(r.type())) {
            deliveries.save(new NotificationDelivery(n.getId(), r.userId(), Channel.EMAIL, null, now));
        }
        events.publishEvent(NotificationsCreatedEvent.single(n.getId()));
        return Optional.of(n);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
