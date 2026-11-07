package com.metroticket_api.notification.campaign;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationCampaignRepository extends JpaRepository<NotificationCampaign, UUID> {

    @Query(value = """
            SELECT id FROM notification_campaign
            WHERE status = 'SCHEDULED' AND scheduled_at <= :now
            ORDER BY scheduled_at LIMIT 1
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<UUID> lockDue(@Param("now") Instant now);

    Page<NotificationCampaign> findByStatusOrderByCreatedAtDesc(CampaignStatus status, Pageable pageable);

    Page<NotificationCampaign> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByAnnouncementId(Long announcementId);
}
