package com.metroticket_api.notification;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.metroticket_api.notification.MyNotificationSettingsService.DeviceView;
import com.metroticket_api.notification.MyNotificationSettingsService.PreferenceView;
import com.metroticket_api.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/me")
@Tag(name = "My notifications", description = "Realtime (SSE), thiết bị nhận push, tuỳ chọn nhận thông báo")
public class MyNotificationSettingsController {

    public record RegisterDeviceRequest(@NotBlank @Size(max = 512) String token, @NotNull UserDevice.Platform platform,
            @Size(max = 40) String appVersion) {
    }

    public record TokenRequest(@NotBlank @Size(max = 512) String token) {
    }

    public record PreferenceRequest(Boolean pushEnabled, Boolean emailEnabled, Boolean promotionEnabled,
            Boolean reminderEnabled) {
    }

    private final MyNotificationSettingsService service;
    private final NotificationStreamService stream;

    public MyNotificationSettingsController(MyNotificationSettingsService service, NotificationStreamService stream) {
        this.service = service;
        this.stream = stream;
    }

    @Operation(summary = "Nhận thông báo realtime (Server-Sent Events)",
            description = "Gửi kèm Authorization: Bearer. Sự kiện 'ready' {unread} khi kết nối, 'notification' khi có thông báo mới.")
    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return stream.open(CurrentUser.id());
    }

    @GetMapping("/notification-preferences")
    public PreferenceView preferences() {
        return service.preferences(CurrentUser.id());
    }

    @Operation(summary = "Bật/tắt push, email, khuyến mãi, nhắc nhở", description = "Thông báo bảo mật luôn được gửi.")
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/notification-preferences")
    public PreferenceView updatePreferences(@Valid @RequestBody PreferenceRequest r) {
        return service.updatePreferences(CurrentUser.id(), r.pushEnabled(), r.emailEnabled(), r.promotionEnabled(),
                r.reminderEnabled());
    }

    @GetMapping("/devices")
    public List<DeviceView> devices() {
        return service.myDevices(CurrentUser.id());
    }

    @Operation(summary = "Đăng ký token FCM của thiết bị", description = "Gọi sau khi đăng nhập và mỗi khi FCM cấp token mới.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/devices")
    public DeviceView register(@Valid @RequestBody RegisterDeviceRequest r) {
        return service.register(CurrentUser.id(), r.token(), r.platform(), r.appVersion());
    }

    @Operation(summary = "Gỡ thiết bị (khi đăng xuất)")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@Valid @RequestBody TokenRequest r) {
        service.unregister(CurrentUser.id(), r.token());
    }
}
