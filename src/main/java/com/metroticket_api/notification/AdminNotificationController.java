package com.metroticket_api.notification;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.notification.NotificationDtos.SendNotificationRequest;
import com.metroticket_api.notification.NotificationDtos.SendNotificationResult;

@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {

    private final NotificationService notifications;

    public AdminNotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    public SendNotificationResult send(@Valid @RequestBody SendNotificationRequest req) {
        boolean all = Boolean.TRUE.equals(req.all());
        boolean hasUsers = req.userIds() != null && !req.userIds().isEmpty();
        if (all == hasUsers) {
            // Bắt buộc chọn rõ, tránh gửi nhầm cho tất cả khi danh sách người nhận bị rỗng
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chọn đúng một trong hai: all hoặc userIds");
        }
        String title = req.title().trim();
        String body = req.body().trim();
        return new SendNotificationResult(all
                ? notifications.sendToAll(title, body)
                : notifications.sendTo(req.userIds(), title, body));
    }
}
