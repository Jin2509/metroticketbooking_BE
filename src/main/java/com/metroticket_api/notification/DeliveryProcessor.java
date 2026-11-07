package com.metroticket_api.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.mail.MailMessage;
import com.metroticket_api.mail.MailTemplates;
import com.metroticket_api.notification.NotificationDelivery.Channel;
import com.metroticket_api.notification.push.PushSender;
import com.metroticket_api.user.UserRepository;

/** Các bước có transaction của DeliveryWorker (tách bean để @Transactional chạy qua proxy). */
@Component
public class DeliveryProcessor {

    static final int MAX_ATTEMPTS = 5;
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /** Việc cần gửi, đã nạp đủ dữ liệu để gửi ngoài transaction. */
    public record Job(Long id, Channel channel, String deviceToken, PushSender.Message push, MailMessage mail) {
    }

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final UserDeviceRepository devices;
    private final UserRepository users;
    private final MailTemplates mailTemplates;
    private final Clock clock;

    public DeliveryProcessor(NotificationDeliveryRepository deliveries, NotificationRepository notifications,
            UserDeviceRepository devices, UserRepository users, MailTemplates mailTemplates, Clock clock) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.devices = devices;
        this.users = users;
        this.mailTemplates = mailTemplates;
        this.clock = clock;
    }

    @Transactional
    public List<Long> claim(int limit) {
        Instant now = clock.instant();
        deliveries.releaseStale(now.minus(STALE_AFTER));
        List<Long> ids = deliveries.lockDue(now, limit);
        if (!ids.isEmpty()) {
            deliveries.markSending(ids, now);
        }
        return ids;
    }

    /** null = không còn gì để gửi (đã bị bỏ qua và đánh dấu). */
    @Transactional
    public Job load(Long id) {
        NotificationDelivery d = deliveries.findById(id).orElse(null);
        if (d == null || d.getStatus() != NotificationDelivery.Status.SENDING) {
            return null;
        }
        Notification n = notifications.findById(d.getNotificationId()).orElse(null);
        if (n == null) {
            d.skipped("Thông báo đã bị xoá");
            return null;
        }
        if (d.getChannel() == Channel.PUSH) {
            UserDevice device = devices.findById(d.getDeviceId()).orElse(null);
            if (device == null || device.getDisabledAt() != null || !device.getUserId().equals(d.getUserId())) {
                d.skipped("Thiết bị đã gỡ / chuyển tài khoản");
                return null;
            }
            Map<String, String> data = new java.util.HashMap<>();
            data.put("notificationId", n.getId().toString());
            data.put("type", n.getType().name());
            if (n.getLink() != null) {
                data.put("link", n.getLink());
            }
            return new Job(id, Channel.PUSH, device.getToken(), new PushSender.Message(n.getTitle(), n.getBody(),
                    n.getPriority() == NotificationPriority.HIGH, data), null);
        }
        return users.findById(d.getUserId())
                .map(u -> new Job(id, Channel.EMAIL, null, null,
                        mailTemplates.accountNotice(u.getEmail(), u.getFullName(), n.getTitle(), n.getBody())))
                .orElseGet(() -> {
                    d.skipped("Không tìm thấy người nhận");
                    return null;
                });
    }

    @Transactional
    public void sent(Long id) {
        deliveries.findById(id).ifPresent(d -> d.sent(clock.instant()));
    }

    @Transactional
    public void failed(Long id, PushSender.Outcome outcome, String error) {
        NotificationDelivery d = deliveries.findById(id).orElse(null);
        if (d == null) {
            return;
        }
        if (outcome == PushSender.Outcome.INVALID_TOKEN) {
            d.failed(error);
            if (d.getDeviceId() != null) {
                devices.findById(d.getDeviceId()).ifPresent(device -> device.disable(clock.instant()));
            }
            return;
        }
        if (outcome == PushSender.Outcome.RETRY && d.getAttempts() < MAX_ATTEMPTS) {
            // backoff 30s, 1m, 2m, 4m
            d.retry(error, clock.instant().plusSeconds(30L << (d.getAttempts() - 1)));
            return;
        }
        d.failed(error);
    }
}
