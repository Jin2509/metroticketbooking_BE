package com.metroticket_api.notification;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.metroticket_api.notification.NotificationDtos.NotificationView;

/**
 * Đẩy thông báo realtime qua Server-Sent Events cho web/app đang mở. Kết nối giữ trong bộ nhớ của instance:
 * chạy nhiều instance thì người dùng nối vào instance khác chỉ nhận qua push/polling (xem report).
 */
@Service
public class NotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamService.class);
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_PER_USER = 5;
    static final int MAX_TOTAL = 5_000;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final NotificationRepository notifications;

    public NotificationStreamService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public SseEmitter open(UUID userId) {
        List<SseEmitter> mine = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        if (mine.size() >= MAX_PER_USER || total.get() >= MAX_TOTAL) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều kết nối thông báo đang mở");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        mine.add(emitter);
        total.incrementAndGet();
        Runnable remove = () -> {
            if (mine.remove(emitter)) {
                total.decrementAndGet();
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("unread", notifications.countByUserIdAndReadAtIsNull(userId))));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    public Set<UUID> connectedUsers() {
        return emitters.keySet();
    }

    int connections() {
        return total.get();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(NotificationsCreatedEvent event) {
        if (emitters.isEmpty()) {
            return;
        }
        List<Notification> created = event.campaignId() != null
                ? notifications.findByCampaignForUsers(event.campaignId(), Set.copyOf(emitters.keySet()))
                : notifications.findAllById(event.notificationIds());
        for (Notification n : created) {
            send(n.getUserId(), NotificationView.of(n));
        }
    }

    void send(UUID userId, NotificationView view) {
        for (SseEmitter emitter : emitters.getOrDefault(userId, List.of())) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(view, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }
    }

    /** Giữ kết nối qua proxy/load balancer (thường cắt kết nối im lặng sau 60 giây). */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.forEach((user, list) -> list.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }));
        emitters.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (log.isDebugEnabled()) {
            log.debug("SSE thông báo: {} kết nối", total.get());
        }
    }
}
