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

    /** Chống gửi trùng thông báo tự động ở mức DB (an toàn khi chạy nhiều instance). Trả 1 nếu đã chèn. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into notification (id, user_id, type, title, body, created_at, priority, link, dedupe_key)
            values (:id, :userId, :type, :title, :body, :now, :priority, :link, :dedupeKey)
            on conflict (user_id, dedupe_key) where dedupe_key is not null do nothing
            """)
    int insertIfAbsent(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("type") String type,
            @org.springframework.data.repository.query.Param("title") String title,
            @org.springframework.data.repository.query.Param("body") String body,
            @org.springframework.data.repository.query.Param("now") Instant now,
            @org.springframework.data.repository.query.Param("priority") String priority,
            @org.springframework.data.repository.query.Param("link") String link,
            @org.springframework.data.repository.query.Param("dedupeKey") String dedupeKey);

    long countByCampaignId(UUID campaignId);

    long countByCampaignIdAndReadAtIsNotNull(UUID campaignId);

    @Query("select n from Notification n where n.campaignId = :campaignId and n.userId in :userIds")
    java.util.List<Notification> findByCampaignForUsers(
            @org.springframework.data.repository.query.Param("campaignId") UUID campaignId,
            @org.springframework.data.repository.query.Param("userIds") java.util.Collection<UUID> userIds);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteOlderThan(Instant before);
}
