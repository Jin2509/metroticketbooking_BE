package com.metroticket_api.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Thiết bị nhận push và tuỳ chọn nhận thông báo của chính người dùng. */
@Service
public class MyNotificationSettingsService {

    /** Mỗi tài khoản tối đa 10 thiết bị nhận push (đổi máy nhiều lần thì thiết bị cũ nhất bị tắt). */
    static final int MAX_DEVICES = 10;

    public record PreferenceView(boolean pushEnabled, boolean emailEnabled, boolean promotionEnabled,
            boolean reminderEnabled) {
        static PreferenceView of(NotificationPreference p) {
            return new PreferenceView(p.isPushEnabled(), p.isEmailEnabled(), p.isPromotionEnabled(), p.isReminderEnabled());
        }
    }

    public record DeviceView(Long id, UserDevice.Platform platform, String appVersion, Instant lastSeenAt) {
        static DeviceView of(UserDevice d) {
            return new DeviceView(d.getId(), d.getPlatform(), d.getAppVersion(), d.getLastSeenAt());
        }
    }

    private final NotificationPreferenceRepository preferences;
    private final UserDeviceRepository devices;
    private final Clock clock;

    public MyNotificationSettingsService(NotificationPreferenceRepository preferences, UserDeviceRepository devices,
            Clock clock) {
        this.preferences = preferences;
        this.devices = devices;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferenceView preferences(UUID userId) {
        return PreferenceView.of(preferences.of(userId));
    }

    @Transactional
    public PreferenceView updatePreferences(UUID userId, Boolean push, Boolean email, Boolean promotion, Boolean reminder) {
        NotificationPreference p = preferences.findById(userId).orElseGet(() -> new NotificationPreference(userId, clock.instant()));
        p.update(push, email, promotion, reminder, clock.instant());
        return PreferenceView.of(preferences.save(p));
    }

    /** Đăng ký/làm mới token FCM. Token đã thuộc tài khoản khác (đăng nhập tài khoản khác trên cùng máy) → chuyển sang. */
    @Transactional
    public DeviceView register(UUID userId, String token, UserDevice.Platform platform, String appVersion) {
        Instant now = clock.instant();
        UserDevice device = devices.findByToken(token.trim()).orElse(null);
        if (device == null) {
            var active = devices.findByUserIdAndDisabledAtIsNull(userId);
            if (active.size() >= MAX_DEVICES) {
                active.stream().min(java.util.Comparator.comparing(UserDevice::getLastSeenAt))
                        .ifPresent(oldest -> oldest.disable(now));
            }
            device = devices.save(new UserDevice(userId, token.trim(), platform, appVersion, now));
        } else {
            device.refresh(userId, platform, appVersion, now);
        }
        return DeviceView.of(device);
    }

    /** Gọi khi đăng xuất / tắt thông báo trên máy: chỉ gỡ được token của chính mình. */
    @Transactional
    public void unregister(UUID userId, String token) {
        UserDevice device = devices.findByToken(token.trim())
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thiết bị"));
        device.disable(clock.instant());
    }

    @Transactional(readOnly = true)
    public java.util.List<DeviceView> myDevices(UUID userId) {
        return devices.findByUserIdAndDisabledAtIsNull(userId).stream().map(DeviceView::of).toList();
    }
}
