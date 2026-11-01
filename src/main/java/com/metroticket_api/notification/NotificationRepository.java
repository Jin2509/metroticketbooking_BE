package com.metroticket_api.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNull(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant now);

    /** Gửi cho mọi tài khoản đang hoạt động trong một câu lệnh, không nạp toàn bộ user vào bộ nhớ. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into notification (id, user_id, type, title, body, created_at)
            select gen_random_uuid(), u.id, :type, :title, :body, :now
            from app_user u where u.status = 'ACTIVE'
            """)
    int insertForAllActiveUsers(String type, String title, String body, Instant now);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteOlderThan(Instant before);
}
