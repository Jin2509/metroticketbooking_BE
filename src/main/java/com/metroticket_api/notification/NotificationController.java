package com.metroticket_api.notification;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.notification.NotificationDtos.NotificationView;
import com.metroticket_api.notification.NotificationDtos.UnreadCountView;
import com.metroticket_api.security.CurrentUser;

/** Mỗi người chỉ thấy và thao tác trên thông báo của chính mình. */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Page<NotificationView> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        return notifications.list(CurrentUser.id(), unreadOnly, pageable).map(NotificationView::of);
    }

    @GetMapping("/unread-count")
    public UnreadCountView unreadCount() {
        return new UnreadCountView(notifications.unreadCount(CurrentUser.id()));
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        notifications.markRead(CurrentUser.id(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        notifications.markAllRead(CurrentUser.id());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        notifications.delete(CurrentUser.id(), id);
    }
}
