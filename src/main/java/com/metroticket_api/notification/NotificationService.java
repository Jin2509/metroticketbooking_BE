package com.metroticket_api.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.mail.MailRequested;
import com.metroticket_api.mail.MailTemplates;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;

/**
 * Thông báo trong ứng dụng: lưu trong DB, web và mobile đọc chung qua API.
 * notify chạy chung transaction với thao tác gây ra thông báo, nên thao tác rollback thì không có thông báo.
 */
@Service
public class NotificationService {

    private static final Duration RETENTION = Duration.ofDays(180);

    private final NotificationRepository repository;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final MailTemplates mailTemplates;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, UserRepository users,
            ApplicationEventPublisher events, MailTemplates mailTemplates, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.events = events;
        this.mailTemplates = mailTemplates;
        this.clock = clock;
    }

    @Transactional
    public Notification notify(UUID userId, NotificationType type, String title, String body) {
        return repository.save(new Notification(userId, type, title, body, clock.instant()));
    }

    /** Như notify, và gửi thêm email sau khi transaction commit. */
    @Transactional
    public Notification notifyWithEmail(User user, NotificationType type, String title, String body) {
        events.publishEvent(new MailRequested(mailTemplates.accountNotice(user.getEmail(), user.getFullName(), title, body)));
        return notify(user.getId(), type, title, body);
    }

    /** Luôn mới nhất trước, không nhận sort từ client. */
    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, boolean unreadOnly, Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return unreadOnly
                ? repository.findByUserIdAndReadAtIsNull(userId, newestFirst)
                : repository.findByUserId(userId, newestFirst);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        load(userId, id).markRead(clock.instant());
    }

    @Transactional
    public void markAllRead(UUID userId) {
        repository.markAllRead(userId, clock.instant());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(load(userId, id));
    }

    @Transactional
    public int sendToAll(String title, String body) {
        return repository.insertForAllActiveUsers(NotificationType.ANNOUNCEMENT.name(), title, body, clock.instant());
    }

    @Transactional
    public int sendTo(Set<UUID> userIds, String title, String body) {
        if (users.findAllById(userIds).size() != userIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Có người nhận không tồn tại");
        }
        Instant now = clock.instant();
        List<Notification> notifications = userIds.stream()
                .map(id -> new Notification(id, NotificationType.ANNOUNCEMENT, title, body, now))
                .toList();
        return repository.saveAll(notifications).size();
    }

    /** Xoá thông báo quá 180 ngày, chạy lúc 4h sáng mỗi ngày. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void purgeOld() {
        repository.deleteOlderThan(clock.instant().minus(RETENTION));
    }

    /** Thông báo của người khác trả 404, không để lộ là id đó có tồn tại. */
    private Notification load(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo"));
    }
}
