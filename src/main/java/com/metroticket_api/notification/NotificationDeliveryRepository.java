package com.metroticket_api.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    interface ChannelStats {
        String getChannel();
        String getStatus();
        long getTotal();
    }

    @Query(value = """
            SELECT id FROM notification_delivery
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Long> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("update NotificationDelivery d set d.status = com.metroticket_api.notification.NotificationDelivery.Status.SENDING, "
            + "d.lockedAt = :now, d.attempts = d.attempts + 1 where d.id in :ids")
    int markSending(@Param("ids") List<Long> ids, @Param("now") Instant now);

    @Modifying
    @Query("update NotificationDelivery d set d.status = com.metroticket_api.notification.NotificationDelivery.Status.PENDING, "
            + "d.lockedAt = null where d.status = com.metroticket_api.notification.NotificationDelivery.Status.SENDING "
            + "and d.lockedAt < :staleBefore")
    int releaseStale(@Param("staleBefore") Instant staleBefore);

    @Query(value = """
            SELECT d.channel AS channel, d.status AS status, count(*) AS total
            FROM notification_delivery d JOIN notification n ON n.id = d.notification_id
            WHERE n.campaign_id = :campaignId GROUP BY d.channel, d.status""", nativeQuery = true)
    List<ChannelStats> statsForCampaign(@Param("campaignId") UUID campaignId);
}
